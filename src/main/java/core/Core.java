package core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import com.alibaba.jvm.sandbox.api.*;
import com.alibaba.jvm.sandbox.api.event.*;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatcher;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.dmall.vltava.domain.enums.MockTypeEnum;
import com.dmall.vltava.domain.enums.TaskStatusEnum;
import com.dmall.vltava.domain.mock.MockActionVO;
import com.dmall.vltava.domain.mock.SleepTimeVO;
import core.domain.CoreBO;
import core.domain.EventBO;
import core.domain.TraceContext;
import core.listener.VltavaEventListener;
import core.progress.VltavaProgress;
import core.trace.Tracer;
import core.trace.TtlConcurrentAdvice;
import http.HttpService;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.common.utils.PojoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.kohsuke.MetaInfServices;
import util.LogbackUtils;
import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * @author Rob
 */
@MetaInfServices(Module.class)
@Information(id = "vltava-agent", version = "0.0.1", author = "Rob")
public class Core implements Module, ModuleLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(Core.class);
    private static final Logger paramLogger = LoggerFactory.getLogger("IN_OUT");
    private static final String RPC_CONTEXT_CLASS = "org.apache.dubbo.rpc.RpcContext";
    private static final String HTTP_HEADER_CLASS = "org.springframework.web.context.request.RequestContextHolder";
    private static final String HTTP_ATTRIBUTES_CLASS = "org.springframework.web.context.request.ServletRequestAttributes";
    private static final String REQUEST_FACADE ="org.apache.catalina.connector.RequestFacade";
    private static final String VLTAVA_MOCK_KEY = "vltavaMockKey";
    private static final Pattern PATTERN = Pattern.compile("(?<=vltavaMockKey\":\").*?(?=\",)");
    private static Boolean initConcurrent = true;
    private static HttpService httpService;
    /**
     * key = reference
     */
    private static final Map<String, CoreBO> CORE_MAP = new HashMap<>();
    private static final Map<Integer, EventBO> MATCH_MAP = new HashMap<>();

    @Resource
    public static ModuleEventWatcher moduleEventWatcher;

    @Override
    public void onLoad() throws Throwable {
        logger.info("onLoad...");
    }

    @Override
    public void onUnload() throws Throwable {
        logger.info("onUnLoad...");
        CORE_MAP.forEach((integer, coreBO) -> coreBO.getEventWatcher().onUnWatched());
        httpService.stop();
    }

    @Override
    public void onActive() throws Throwable {
        LogbackUtils.init();
        logger.info("onActive...");
        httpService = Register.register();
        if (initConcurrent) {
            TtlConcurrentAdvice.watcher(moduleEventWatcher).watch();
            initConcurrent = true;
            logger.info("init concurrent finished.");
        }
    }

    @Override
    public void onFrozen() throws Throwable {
        logger.info("onFrozen...");
        httpService.stop();
    }

    @Override
    public void loadCompleted() {
        logger.info("loadCompleted...");
    }

    public static Boolean start(String reference) {
        logger.info("##### start ");
        if (CORE_MAP.containsKey(reference)) {
            return true;
        }
        logger.info("##### reference is " + reference);
        CoreBO coreBO = init(reference);
        EventWatcher eventWatcher = null;
        EventWatchBuilder.IBuildingForWatching iBuildingForWatching = new EventWatchBuilder(moduleEventWatcher)
                .onClass(coreBO.getClassName())
                .includeSubClasses()
                .onBehavior(coreBO.getMethodName())
                .onWatching()
                .withProgress(new VltavaProgress(coreBO));
        eventWatcher = iBuildingForWatching.onWatch(new VltavaEventListener() {

                                                        @Override
                                                        public void onEvent(Event event) throws Throwable {
                                                            super.onEvent(event);
                                                            if (!DataCenter.isStart(reference)) {
                                                                logger.info("pass due to status...");
                                                                return;
                                                            }
                                                            EventBO eventBO = new EventBO();
                                                            eventBO.setReference(coreBO.getReference());
                                                            try {
                                                                switch (event.type) {
                                                                    case BEFORE:
                                                                        BeforeEvent beforeEvent = (BeforeEvent) event;
                                                                        logger.info("mock请求参数(BEFORE)！eventBO is " + JSONObject.toJSONString(eventBO));
                                                                        logger.info("eventBO的mockey为:" + eventBO.getMockKey());
                                                                        mockBefore(beforeEvent, eventBO);
                                                                        break;
                                                                    case RETURN:
                                                                        ReturnEvent returnEvent = (ReturnEvent) event;
                                                                        logger.info("mock请求参数(RETURN)！eventBO is " + JSONObject.toJSONString(eventBO));
                                                                        mockReturn(returnEvent, eventBO);
                                                                        break;
                                                                    default:
                                                                        break;
                                                                }
                                                            } catch (ProcessControlException e) {
                                                                throw e;
                                                            } catch (Throwable e) {
                                                                logger.error(e.getMessage(), e);
                                                                throw e;
                                                            } finally {
                                                                TraceContext traceContext = Tracer.getContext();
                                                                if (event.type.equals(Event.Type.RETURN) || event.type.equals(Event.Type.THROWS) || event.type.equals(Event.Type.IMMEDIATELY_RETURN) || event.type.equals(Event.Type.IMMEDIATELY_THROWS)) {
                                                                    if (traceContext != null && traceContext.getEntranceReference().equals(coreBO.getReference())) {
                                                                        logger.info("ThreadLocal removed: " + Thread.currentThread().getName());
                                                                        Tracer.end();
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                , Event.Type.BEFORE, Event.Type.RETURN, Event.Type.THROWS);
        coreBO.setEventWatcher(eventWatcher);
        if (coreBO.getEventWatcher() != null && !coreBO.getWatching()) {
            eventWatcher.onUnWatched();
            logger.info("卸载完成 " + coreBO.getReference());
        }
        return coreBO.getWatching();
    }

    public static void close(String reference) {
        if (!CORE_MAP.containsKey(reference)) {
            return;
        }
        if (CORE_MAP.get(reference).getEventWatcher() != null) {
            CORE_MAP.get(reference).getEventWatcher().onUnWatched();
        }
        CORE_MAP.remove(reference);
    }

    public static void close(CoreBO coreBO) {
        String reference = coreBO.getClassName() + "#" + coreBO.getMethodName();
        close(reference);
    }

    private static CoreBO init(String reference) {
        CoreBO coreBO = null;
        coreBO = new CoreBO();
        coreBO.setReference(reference);
        coreBO.setClassName(reference.split("#")[0]);
        coreBO.setMethodName(reference.split("#")[1]);
        coreBO.setFinish(false);
        coreBO.setWatching(false);
        CORE_MAP.put(reference, coreBO);
        return coreBO;
    }

    private static void mockBefore(BeforeEvent beforeEvent, EventBO eventBO) throws ProcessControlException, InterruptedException {
        logger.info("##本次处理的类是：" + beforeEvent.javaClassName);
        String tid = getTid(beforeEvent);
        logger.info("##参数为：" + beforeEvent.argumentArray);
        String params = JSONArray.toJSONString(beforeEvent.argumentArray);
        paramLogger.info(String.format("@ %s @ 【class】：%s【method】：%s【param】: %s", beforeEvent.invokeId, eventBO.getReference().split("#")[0],
                eventBO.getReference().split("#")[1], params));
        matchAction(beforeEvent, eventBO, params, tid);
        logger.info("@ " + JSON.toJSONString(Tracer.getContext()) + " @ " + "mock before...");
        // 未匹配
        if (!eventBO.getMatch()) {
            return;
        } else {
            // 入口方法
            if (eventBO.getMatchedMockAction().getEntrance() != null && eventBO.getMatchedMockAction().getEntrance()) {
                logger.info("111111111111111");
                return;
            }
            // 非入口
            logger.info("1111111111111112");
            try{
                SleepTimeVO sleepTimeVO = eventBO.getMatchedMockAction().getSleepTimeVO();
                Integer time;
                if (sleepTimeVO.getNeedRandom() == 1) {
                    time = new Random().nextInt(sleepTimeVO.getRandomEnd() - sleepTimeVO.getRandomStart()) + sleepTimeVO.getRandomStart();
                } else {
                    time = sleepTimeVO.getBaseTime();
                }
                Thread.sleep(time);
            } catch (Exception e){
                logger.info("##未获取到休眠时间");
            }
            Tracer.getContext().startInvoke(beforeEvent);
        }
        if (eventBO.getMatchedMockAction().getMockType().equals(MockTypeEnum.RETURN_VALUE.getKey())) {
            logger.info("1111111111111113");
            Class clz = null;
            try {
                clz = beforeEvent.javaClassLoader.loadClass(beforeEvent.javaClassName);
            } catch (ClassNotFoundException e) {
                logger.error(e.getMessage(), e);
            }
            for (Method method : clz.getMethods()) {
                logger.info("1111111111111114");
                logger.info(JSON.toJSONString(method.getName()));
                logger.info(JSON.toJSONString(beforeEvent.javaMethodName));
                if (method.getName().equals(beforeEvent.javaMethodName)) {
                    logger.info("1111111111111115");
                    Object returnObj = null;
                    updateDynamic(eventBO, beforeEvent);
                    logger.info(String.format("@" + beforeEvent.invokeId + "@ " + "will return expect value: %s", eventBO.getMatchedMockAction().getExpectValue()));
                    // mock数据中的${xxx}格式，从请求的参数中找到xxx的值来替换原mock数据
                    String mockData = formatStr(eventBO.getMatchedMockAction().getExpectValue(), beforeEvent.argumentArray);
                    logger.info("替换变量后的返回结果为："+ mockData);
                    if (JSON.parseObject(mockData).containsKey("class")) {
                        returnObj = PojoUtils.realize(JSON.parseObject(mockData), method.getReturnType(), method.getGenericReturnType());
                    } else {
                        returnObj = JSON.parseObject(mockData, method.getGenericReturnType());
                    }
                    logger.info("即将返回的调用结果:" + JSONObject.toJSONString(returnObj));
                    ProcessController.returnImmediately(returnObj);
                }
            }
        } else if (eventBO.getMatchedMockAction().getMockType().equals(MockTypeEnum.THROW_EXCEPTION.getKey())) {
            logger.info(String.format("@" + beforeEvent.invokeId + "@ " + "will throw Exception: %s", eventBO.getMatchedMockAction().getErrorMsg()));
            ProcessController.throwsImmediately(new Exception(eventBO.getMatchedMockAction().getErrorMsg()));
        }
    }

    /*
        查找requestStr中包含"${}"格式的关键字，去response中查找到后并替换原requestStr的数据并返回
     */
    public static String formatStr(String responseStr, Object[] request){
        try{
            Pattern pattern = Pattern.compile("\\$\\{([\\s\\S]+?)\\}");
            Matcher matcher = pattern.matcher(responseStr);
            StringBuffer stringBuffer = new StringBuffer();
            while (matcher.find()){
                String substring = responseStr.substring(matcher.start(), matcher.end());
                String k = matcher.group(1);
                String targetV = String.valueOf(findKV(k, request));
                matcher.appendReplacement(stringBuffer, matcher.group().replace(substring, targetV));
            }
            matcher.appendTail(stringBuffer);
            return String.valueOf(stringBuffer);
        } catch (Exception e){
            logger.error(e.getMessage(), e);
            return responseStr;
        }
    }



    /*
      递归查找response对象中包含指定key的值
     */
    public static Object findKV(String key, Object[] response){
        for (Object obj: response) {
            try{
                if (obj instanceof List){
                    Object[] objArr = ((List) obj).toArray();
                    return findKV(key, objArr);
                }
                if (obj instanceof Object[]){
                    Object[] objArr = (Object[]) obj;
                    return findKV(key, objArr);
                }
                String objStr;
                if (obj instanceof String) {
                    objStr = (String) obj;
                }else {
                    objStr = JSON.toJSONString(obj);
                }
                JSONObject jsonObj = JSON.parseObject(objStr);
                for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
                    if (key.equals(entry.getKey())) {
                        return entry.getValue();
                    } else {
                        Object [] objArray;
                        if (entry.getValue().getClass().isArray()){
                            objArray = (Object [])  entry.getValue();
                        } else {
                            objArray = new Object[]{entry.getValue()};
                        }
                        Object result = findKV(key, objArray);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            } catch (Exception e){
                continue;
            }
        }
        return null;
    }



//    @Test
//    public static void replaceTest() {
//        String requestStr = "{\"msg\":\"成功\",\"test_mock_key\":\"${tid}\",\"code\":\"${startTime}\",\"respMsg\":\"${mockKey}\",\"resp_data\":{\"need_query\":\"${entranceReference}\",\"merchant_no\":\"822651048160KWL\",\"account_type\":\"WECHAT\",\"acc_resp_fields\":{\"up_iss_addn_data\":\"\",\"promotion_detail\":\"\",\"user_id\":\"\",\"open_id\":\"oVxsc1c6X5f0D67nPiwFntNovi4s\",\"up_coupon_info\":\"\",\"alipay_store_id\":\"\",\"trade_info\":\"\"},\"payer_amount\":\"1\",\"acc_discount_amount\":\"\",\"trade_time\":\"20230214093910\",\"bank_type\":\"OTHERS\",\"acc_settle_amount\":\"1\",\"acc_trade_no\":\"4200059241202302143291091198\",\"remark\":\"\",\"log_no\":\"66213787774554\",\"card_type\":\"99\",\"acc_mdiscount_amount\":\"0\",\"out_trade_no\":\"29000216763387490214093901000102\",\"total_amount\":\"1\",\"trade_no\":\"20230214110113130166213787774554\"},\"respCode\":\"BBS00000\"}";
//        TraceContext test = new TraceContext("123", "456", "789");
////        System.out.println(JSON.toJSONString(test));
//        TraceContext test1 = new TraceContext("321", "321", "555");
////        String test = "{\"tid\": \"888\", \"mockKey\":\"777\", \"entranceReference\": \"666\"}";
//
//        List<TraceContext> payChannelParams = new ArrayList<>();
//        payChannelParams.add(test);
//        Object ss=(Object) payChannelParams;
////        payChannelParams.add(test1);
////        JSONObject =
//        Object[] strobj = new Object[]{test};
//
//        ClassLoader ss1 = new ClassLoader() {
//            @Override
//            public Class<?> loadClass(String name) throws ClassNotFoundException {
//                return super.loadClass(name);
//            }
//        };
//        Object[] obj1 = new Object[]{""};
////        BeforeEvent event = new BeforeEvent(123, 321, ss1, "yyy", "zzz", "desc", "target", strobj);
//        Object[] objar = new Object[]{strobj,"https://s2.lakala.com/api/v3/labs/trans/micropay"};
//        System.out.println(JSON.toJSONString(objar));
//        Object result = findKV("tid", strobj);
////        String result = formatStr(requestStr, event.argumentArray);
////        String result = formatStr(requestStr, JSON.parseObject(test));
//        System.out.print(result);
//    }


    private static void updateDynamic(EventBO eventBO, BeforeEvent beforeEvent) {
        if (eventBO.getMatchedMockAction().getDynamicChange() != null && eventBO.getMatchedMockAction().getDynamicChange()) {
            logger.info("dynamic = true");
            Object dynamicValue = JSONPath.eval(beforeEvent.argumentArray, eventBO.getMatchedMockAction().getRequestPath());
            logger.info("动态替换：" + dynamicValue);
            Object tmp = null;
            try {
                tmp = JSON.parseObject(eventBO.getMatchedMockAction().getExpectValue());
            } catch (Exception e) {
                tmp = JSON.parseArray(eventBO.getMatchedMockAction().getExpectValue());
            }
            JSONPath.set(tmp, eventBO.getMatchedMockAction().getResponsePath(), dynamicValue.toString());
            eventBO.getMatchedMockAction().setExpectValue(JSON.toJSONString(tmp));
            logger.info("替换后结果：" + eventBO.getMatchedMockAction().getExpectValue());
        }
    }


    private static void mockReturn(ReturnEvent returnEvent, EventBO inputEventBO) throws ProcessControlException {
        logger.info("@" + returnEvent.invokeId + "@ " + "mock return...");
        paramLogger.info(String.format("@ %s @ 【class】：%s【method】：%s【response】: %s", returnEvent.invokeId, inputEventBO.getReference().split("#")[0],
                inputEventBO.getReference().split("#")[1], JSON.toJSONString(PojoUtils.generalize(returnEvent.object))));
        EventBO eventBO = null;
        if (MATCH_MAP.containsKey(returnEvent.invokeId)) {
            eventBO = MATCH_MAP.get(returnEvent.invokeId);
            MATCH_MAP.remove(returnEvent.invokeId);
        }
        if (eventBO != null && eventBO.getMatch()) {
            if (eventBO.getMatchedMockAction().getMockType().equals(MockTypeEnum.SLEEP.getKey())) {
                SleepTimeVO sleepTimeVO = eventBO.getMatchedMockAction().getSleepTimeVO();
                Integer time;
                if (sleepTimeVO.getNeedRandom() == 1) {
                    time = new Random().nextInt(sleepTimeVO.getRandomEnd() - sleepTimeVO.getRandomStart()) + sleepTimeVO.getRandomStart();
                } else {
                    time = sleepTimeVO.getBaseTime();
                }
                try {
                    logger.info(String.format("@" + returnEvent.invokeId + "@ " + "will sleep %s ms", time));
                    Thread.sleep(time);
                } catch (InterruptedException e) {

                    e.printStackTrace();
                }
            }
        }
    }

    private static String getTid(BeforeEvent beforeEvent) {
        try {
            Class monitorClz = beforeEvent.javaClassLoader.loadClass("com.dmall.monitor.sdk.Monitor");
            Method method = monitorClz.getMethod("getTraceId");
            Object tid = method.invoke(monitorClz);
            return tid != null ? (String) tid : null;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }


    private static String getParam(BeforeEvent beforeEvent) {
        try {
//            beforeEvent.argumentArray
            Class monitorClz = beforeEvent.javaClassLoader.loadClass("com.dmall.monitor.sdk.Monitor");
            Method method = monitorClz.getMethod("getTraceId");
            Object tid = method.invoke(monitorClz);
            return tid != null ? (String) tid : null;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * 匹配规则
     */
    private static void matchAction(BeforeEvent beforeEvent, EventBO eventBO, String params, String tid) {
        logger.info(" 正则匹配，匹配被调用类(" + beforeEvent.javaClassName + ")的mockKey");
        eventBO.setMatch(false);
        TraceContext traceContext = Tracer.getContext();
        logger.info("上下文为：" + JSON.toJSONString(traceContext));
        if (traceContext == null) {
            Tracer.start(null, eventBO.getReference(), tid);
            implicitParams(beforeEvent, eventBO);
            Tracer.setMockKey(eventBO.getMockKey());
        } else {
            eventBO.setMockKey(traceContext.getMockKey());
        }
        // 请求有key时只匹配key
        if (StringUtils.isNotEmpty(eventBO.getMockKey())) {
            for (MockActionVO mockAction : DataCenter.getActions(eventBO.getReference())) {
                if (eventBO.getMockKey().equals(mockAction.getMockKey()) && mockAction.getTaskStatus().equals(TaskStatusEnum.RUNNING.getKey())) {
                    eventBO.setMatch(true);
                    eventBO.setMatchedMockAction(mockAction);
                    if (mockAction.getEntrance() != null && mockAction.getEntrance()) {
                        Tracer.setMockKey(eventBO.getMockKey());
                    }
                    logger.info("@" + beforeEvent.invokeId + "@ " + "match by mockKey: " + eventBO.getMockKey() + " action: " + JSON.toJSONString(mockAction));
                    break;
                }
            }
        } else {
            if (!eventBO.getMatch()) {
                for (MockActionVO mockAction : DataCenter.getActions(eventBO.getReference())) {
                    if (!mockAction.getTaskStatus().equals(TaskStatusEnum.RUNNING.getKey())) {
                        continue;
                    }
                    //支持多参数匹配按&&分隔
                    String expectKey = mockAction.getExpectKey();
                    boolean isMatch = true;
                    String[] expectKeyList = expectKey.split("&&");
                    for (String expect : expectKeyList) {
                        if (!params.contains(expect.trim())) {
                            isMatch = false;
                            break;
                        }
                    }
                    if (mockAction.getExpectKey() != null && isMatch) {
                        logger.info("@" + beforeEvent.invokeId + "@ " + "match by expectKey: " + mockAction.getExpectKey() + " action: " + JSON.toJSONString(mockAction));
                        eventBO.setMatch(true);
                        eventBO.setMatchedMockAction(mockAction);
                        break;
                    }
                }
            }
        }
        if (!eventBO.getMatch()) {
            logger.info("@" + beforeEvent.invokeId + "@ " + "didn't match any action");
        } else {
            if (eventBO.getMatchedMockAction().getMockType() != null && eventBO.getMatchedMockAction().getMockType().equals(MockTypeEnum.SLEEP.getKey())) {
                MATCH_MAP.put(beforeEvent.invokeId, eventBO);
                logger.info("@" + beforeEvent.invokeId + "@ " + "add MATCH_MAP: " + JSON.toJSONString(MATCH_MAP));
            }
        }
//        logger.info("CORE_MAP: " + JSON.toJSONString(CORE_MAP));
    }

    /**
     * 处理隐式参数
     */
    private static void implicitParams(BeforeEvent beforeEvent, EventBO eventBO) {
        Class httpContext = null;
        Class attributes = null;
        Class rpcContext = null;
        Class facade = null;
        try {
            httpContext = beforeEvent.javaClassLoader.loadClass(HTTP_HEADER_CLASS);
            attributes = beforeEvent.javaClassLoader.loadClass(HTTP_ATTRIBUTES_CLASS);
            rpcContext = beforeEvent.javaClassLoader.loadClass(RPC_CONTEXT_CLASS);
            facade = beforeEvent.javaClassLoader.loadClass(REQUEST_FACADE);
        } catch (ClassNotFoundException e) {
            logger.info("报错信息为1：" + e.getMessage());
            e.printStackTrace();
        }
        try {
            String contextStr = null;
            try {
                //使用类的ClassLoader获取调用时的RPCContext里面的数据，可以对这里进行改造，增加如果是Http请求的情况
                logger.info(beforeEvent.javaClassName + " ##处理RPC的隐式参数...");
                Method getContext = rpcContext.getMethod("getContext");
                logger.info("rpc上下文为：" + String.valueOf(getContext.invoke(rpcContext)));
                logger.info("隐式处理参数，获取rpcContext里面的内容" + JSON.toJSONString(getContext.invoke(rpcContext)));
                contextStr = JSON.toJSONString(getContext.invoke(rpcContext));
            } catch (Exception e) {
                logger.info("报错信息为2：" + e.getMessage());
                e.printStackTrace();
            }
//            Method setAttachment = rpcContext.getMethod("setAttachment");
            if (contextStr.contains(VLTAVA_MOCK_KEY)) {
                Matcher matcher = PATTERN.matcher(contextStr);
                if (matcher.find()) {
                    String mockKey = matcher.group(0);
                    logger.info("@" + beforeEvent.invokeId + "@ " + "mockKey: " + mockKey);
                    eventBO.setMockKey(mockKey);
//                    setAttachment.invoke(rpcContext, VLTAVA_MOCK_KEY, mockKey);
                }
            } else {
                try {
                    logger.info(beforeEvent.javaClassName + " ##处理http请求头部参数...");
                    Method getRequestAttributes = httpContext.getMethod("getRequestAttributes");
                    Object httpRequestFacade = getRequestAttributes.invoke(httpContext);//获取了一个RequestFacade类
                    Method getRequest = attributes.getMethod("getRequest");
                    Object request = getRequest.invoke(httpRequestFacade);//获取了一个RequestFacade类
                    logger.info("request is "+request.toString());

                    Method getFacadeRequest = facade.getMethod("getHeader",String.class);
                    Object header = getFacadeRequest.invoke(request,"vltavaMockKey");
                    logger.info("header is "+header.toString());
                    contextStr = header.toString();
                    if (contextStr!=null) {
                        logger.info("@" + beforeEvent.invokeId + "@ " + "mockKey: " + contextStr);
                        eventBO.setMockKey(contextStr);
//                        setAttachment.invoke(rpcContext, VLTAVA_MOCK_KEY, contextStr);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.info("报错信息为3：" + e.getMessage());
                }
            }
        }catch (Exception e){
            logger.info("报错信息为4：" + e.getMessage());
            e.printStackTrace();
        }
    }
}
