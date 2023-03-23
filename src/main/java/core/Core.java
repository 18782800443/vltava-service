package core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import com.alibaba.jvm.sandbox.api.*;
import com.alibaba.jvm.sandbox.api.event.*;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatcher;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.dmall.fit.boot.spec.jackson.utils.JSONUtil;
import com.dmall.vltava.domain.enums.MockTypeEnum;
import com.dmall.vltava.domain.enums.TaskStatusEnum;
import com.dmall.vltava.domain.mock.MockActionVO;
import com.dmall.vltava.domain.mock.SleepTimeVO;
import com.fasterxml.jackson.core.type.TypeReference;
import core.domain.CoreBO;
import core.domain.EventBO;
import core.domain.TraceContext;
import core.listener.VltavaEventListener;
import core.progress.VltavaProgress;
import core.trace.RcbOpenKey;
import core.trace.Tracer;
import core.trace.TtlConcurrentAdvice;
import http.HttpService;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.common.utils.PojoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.kohsuke.MetaInfServices;
import org.testng.annotations.Test;
import util.FastJsonUtil;
import util.LogbackUtils;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import util.TLinx2Util;
import util.TLinxAESCoder;

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
    private static final String REQUEST_FACADE = "org.apache.catalina.connector.RequestFacade";
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
        String params = JSON.toJSONString(beforeEvent.argumentArray);
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
            try {
                SleepTimeVO sleepTimeVO = eventBO.getMatchedMockAction().getSleepTimeVO();
                Integer time;
                if (sleepTimeVO.getNeedRandom() == 1) {
                    time = new Random().nextInt(sleepTimeVO.getRandomEnd() - sleepTimeVO.getRandomStart()) + sleepTimeVO.getRandomStart();
                } else {
                    time = sleepTimeVO.getBaseTime();
                }
                Thread.sleep(time);
            }catch(Exception e){
                logger.info("未获取到休眠时间，不休眠");
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
                    String mockData="";
                    if (params.contains("https://api.sxpay.shanxinj.com/mct1/payorder")) {
                        logger.info("========================解密=======================");
                        String request = rcbDecrypt(JSON.toJSONString(beforeEvent.argumentArray[1]));
                        logger.info("请求参数是：%s",request);
                        logger.info("========================创建新对象=======================");
                        Object[] reqObj = new Object[1];
                        reqObj[0] = request;
                        logger.info("========================替换参数=======================");
                        mockData = formatStr(eventBO.getMatchedMockAction().getExpectValue(), reqObj);
                    }
                    else {
                        mockData = formatStr(eventBO.getMatchedMockAction().getExpectValue(), beforeEvent.argumentArray);
                    }
                    logger.info("替换变量后的返回结果为：" + mockData);
                    //判断支付请求是否是农商行，如果是农商行，需要对返回结果进行加密
                    if (params.contains("https://api.sxpay.shanxinj.com/mct1/payorder")) {
                        mockData = rcbSign(mockData);
                    }
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

    //山西农商行返回参数加密
    private static String rcbSign(String mockData) {
        RcbOpenKey rcbOpenKey = new RcbOpenKey();
        String openKey = "8e942ca055b53f811e6e77cb047b8dcb";
        String openId = "42a8aef029208f0e027a49cd0d63fb28";
        rcbOpenKey.setOpenId(openId);
        rcbOpenKey.setOpenKey(openKey);
//        MethodInfo methodInfo = Monitor.methodStart(methodKey);
        TreeMap<String, String> param = createCommonParam(rcbOpenKey);

        String body = mockData;
        try {
            //1.data字段内容进行AES加密，再二进制转十六进制(bin2hex)
            String bodyEncrypt = TLinx2Util.handleEncrypt(body, rcbOpenKey.getOpenKey());
            param.put("data", bodyEncrypt);
            param.put("msg", "ok");
            param.put("errcode", "0");
            //2 请求参数签名 按A~z排序，串联成字符串，先进行sha1加密(小写)，再进行md5加密(小写)，得到签名
            TLinx2Util.handleSign(param, rcbOpenKey.getOpenKey());

//            param.remove("open_id");

            //请求参数密文
            String requestEncrypt = FastJsonUtil.writeObjectAsString(param);

            return requestEncrypt;

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    //山西农商行请求参数解密
    private static String rcbDecrypt(String request) {
        RcbOpenKey rcbOpenKey = new RcbOpenKey();
        String openKey = "8e942ca055b53f811e6e77cb047b8dcb";
        String openId = "42a8aef029208f0e027a49cd0d63fb28";
        rcbOpenKey.setOpenId(openId);
        rcbOpenKey.setOpenKey(openKey);

        //拿到响应结果后需要验证签名
        try {
            JSONObject req = JSON.parseObject(request);
            if (StringUtils.isNotEmpty(req.getString("data"))) {
                Boolean checkSign = TLinx2Util.verifySign(JSONObject.parseObject(request), rcbOpenKey.getOpenKey());
                if (checkSign) {
                    String reqData = TLinxAESCoder.decrypt(req.getString("data"), rcbOpenKey.getOpenKey());
                    return reqData;
                } else {
                    System.out.println("验签失败");
                    return null;
                }
            } else {
                return null;
            }

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }


    private static TreeMap<String, String> createCommonParam(RcbOpenKey rcbOpenKey) {
        // 固定参数
        Long timestamp = System.currentTimeMillis() / 1000;
        TreeMap<String, String> commonParam = new TreeMap<String, String>();    // 请求参数的map
        commonParam.put("open_id", rcbOpenKey.getOpenId());
        commonParam.put("timestamp", String.valueOf(timestamp));
        return commonParam;
    }

    /*
        查找requestStr中包含"${}"格式的关键字，去response中查找到后并替换原requestStr的数据并返回
     */
    public static String formatStr(String responseStr, Object[] request) {
        try {
            Pattern pattern = Pattern.compile("\\$\\{([\\s\\S]+?)\\}");
            Matcher matcher = pattern.matcher(responseStr);
            StringBuffer stringBuffer = new StringBuffer();
            while (matcher.find()) {
                String substring = responseStr.substring(matcher.start(), matcher.end());
                String k = matcher.group(1);
                String targetV = String.valueOf(findKV(k, request));
                matcher.appendReplacement(stringBuffer, matcher.group().replace(substring, targetV));
            }
            matcher.appendTail(stringBuffer);
            return String.valueOf(stringBuffer);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return responseStr;
        }
    }


    /*
      递归查找response对象中包含指定key的值
     */
    public static Object findKV(String key, Object[] response) {
        for (Object obj : response) {
            try {
                if (obj instanceof List) {
                    Object[] objArr = ((List) obj).toArray();
                    return findKV(key, objArr);
                }
                if (obj instanceof Object[]) {
                    Object[] objArr = (Object[]) obj;
                    return findKV(key, objArr);
                }
                String objStr;
                if (obj instanceof String) {
                    objStr = (String) obj;
                } else {
                    objStr = JSON.toJSONString(obj);
                }
                JSONObject jsonObj = JSON.parseObject(objStr);
                for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
                    if (key.equals(entry.getKey())) {
                        return entry.getValue();
                    } else {
                        Object[] objArray;
                        if (entry.getValue() == null){
                            continue;
                        }
                        if (entry.getValue().getClass().isArray()) {
                            objArray = (Object[]) entry.getValue();
                        } else {
                            objArray = new Object[]{entry.getValue()};
                        }
                        Object result = findKV(key, objArray);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            } catch (Exception e) {
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
            e.printStackTrace();
        }
        try {
            String contextStr = "";
            try {
                //使用类的ClassLoader获取调用时的RPCContext里面的数据，可以对这里进行改造，增加如果是Http请求的情况
                logger.info(beforeEvent.javaClassName + " ##处理RPC的隐式参数...");
                Method getContext = rpcContext.getMethod("getContext");
                logger.info("隐式处理参数，获取rpcContext里面的内容" + JSON.toJSONString(getContext.invoke(rpcContext)));
                contextStr = JSON.toJSONString(getContext.invoke(rpcContext));
            } catch (Exception e) {
                logger.info(e.getMessage());
                e.printStackTrace();
            }
//            assert rpcContext != null;
            Method setAttachment = rpcContext.getMethod("setAttachment");
            if (contextStr.contains(VLTAVA_MOCK_KEY)) {
                Matcher matcher = PATTERN.matcher(contextStr);
                if (matcher.find()) {
                    String mockKey = matcher.group(0);
                    logger.info("@" + beforeEvent.invokeId + "@ " + "mockKey: " + mockKey);
                    eventBO.setMockKey(mockKey);
                    setAttachment.invoke(rpcContext, VLTAVA_MOCK_KEY, mockKey);
                }
            } else {
                try {
                    logger.info(beforeEvent.javaClassName + " ##处理http请求头部参数...");
                    Method getRequestAttributes = httpContext.getMethod("getRequestAttributes");
                    Object httpRequestFacade = getRequestAttributes.invoke(httpContext);//获取了一个RequestFacade类
                    Method getRequest = attributes.getMethod("getRequest");
                    Object request = getRequest.invoke(httpRequestFacade);//获取了一个RequestFacade类
                    logger.info("request is " + request.toString());

                    Method getFacadeRequest = facade.getMethod("getHeader", String.class);
                    Object header = getFacadeRequest.invoke(request, "vltavaMockKey");
                    logger.info("header is " + header.toString());
                    contextStr = header.toString();
                    if (contextStr != "") {
                        logger.info("@" + beforeEvent.invokeId + "@ " + "mockKey: " + contextStr);
                        eventBO.setMockKey(contextStr);
                        setAttachment.invoke(rpcContext, VLTAVA_MOCK_KEY, contextStr);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.info(e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.info(e.getMessage());
            e.printStackTrace();
        }
    }


    @Test
    public static void test() {
//        logger.info("========================解密=======================");
//        ClassLoader s = new ClassLoader() {
//            @Override
//            public Class<?> loadClass(String name) throws ClassNotFoundException {
//                return super.loadClass(name);
//            }
//        };
//        Object[] argumentArray= new Object[2];
//        argumentArray[0] = "https://api.sxpay.shanxinj.com/mct1/payorder";
//        argumentArray[1] = "{\"data\":\"7c823fa631791803d472288d892cedcd3de8ce45cc2af217e3a22df4ce71a76b614b856fb0aeb840531be8f18cf6b44b2179bf75ca6ecd5b85eabfe729ba5111a3685bef20d6ffd0797e1abff58734ce539a44aa304de7cf2288112165f056c48496139cd4abd97fa90e33d534a53c7940c996525e1836d62ea6cd9dbb95a344eab004cf400fbc8f5aef863b4fd56f9cc59b22251e280c0e121e22b9f021cd26bec85d44e6a7b2be97dd1d5a01ea209a692b071fac29a6f57bacb1f0a6bb84a9eb0fb0f95024df1860363bcae1a07f17ab48f6c6a1df67d2c710f9fa881423c7127c486f8ad2eb7889388859af888cd16ad1e4c082342ca2ae75f11dbe9603be96a90f107fadfefc843bc24d78690be1\",\"open_id\":\"42a8aef029208f0e027a49cd0d63fb28\",\"sign\":\"e67949c644d09298e605c0d84d2fd2c2\",\"timestamp\":\"1678858604\"}";
//        BeforeEvent beforeEvent =  new BeforeEvent(213,21, s, "12","","","",argumentArray);
//        String request = rcbDecrypt(beforeEvent.argumentArray[1].toString());
//        Object[] reqObj = new Object[1];
////        reqObj[0] = "https://api.sxpay.shanxinj.com/mct1/payorder";
//        reqObj[0] = request;
//        System.out.println(reqObj[0]);
////        findKV("trade_amount",reqObj);
//        String responseStr = "{\"ord_id\":\"2147920950\",\"ord_no\":\"9167705372970938039330173\",\"ord_type\":\"2\",\"ord_mct_id\":\"2147920950\",\"ord_shop_id\":\"2147920950\",\"ord_name\":\"(\\u9000\\u6b3e)WeixinSN2-9167722296074920093963567\",\"add_time\":\"2023-02-24 15:20:19\",\"trade_account\":null,\"trade_amount\":\"${trade_amount}\",\"trade_time\":\"2023-02-24 15:20:20\",\"trade_no\":\"4200057835202302229269928572\",\"trade_qrcode\":null,\"trade_pay_time\":\"2023-02-24 15:20:20\",\"remark\":null,\"status\":\"1\",\"original_amount\":\"12724\",\"discount_amount\":\"0\",\"ignore_amount\":\"0\",\"trade_discout_amount\":\"0\",\"original_ord_no\":\"9167722296074920093963567\",\"trade_result\":\"{\\\"return_code\\\":\\\"SUCCESS\\\",\\\"appid\\\":\\\"wxdb5f93f24605b962\\\",\\\"mch_id\\\":\\\"1513869461\\\",\\\"sub_mch_id\\\":\\\"544177450\\\",\\\"result_code\\\":\\\"SUCCESS\\\",\\\"nonce_str\\\":\\\"58242f74e91741d599237c5a7bd3ce8c\\\",\\\"out_refund_no\\\":\\\"9167705372970938039330173\\\",\\\"refund_id\\\":\\\"4200057835202302229269928572\\\",\\\"refund_fee\\\":\\\"2477\\\",\\\"cash_refund_fee\\\":\\\"2477\\\",\\\"coupon_refund_fee\\\":\\\"0\\\",\\\"sign\\\":\\\"MEUCIQD3eL6qWaX0vvadV3K0TQhhAVeoNDRSs5+SnFVBV3h2ZQIgBgVgPBtMZ1E\\\\\\/5D4EO6aEFXXXNI5hhLYOGxp8D5oZh80=\\\"}\",\"currency\":\"CNY\",\"currency_sign\":\"\\u00a5\",\"out_no\":\"${out_no}\",\"pmt_tag\":\"WeixinSN2\",\"pmt_name\":\"\\u5fae\\u4fe1\\u652f\\u4ed8\",\"tag\":null,\"scr_id\":\"0\",\"shop_no\":\"860984435\",\"tml_no\":\"0\",\"ord_trade_no2\":null,\"ord_credit_card\":\"1\"}";
//        String result = formatStr(responseStr,reqObj);
//        System.out.println(result);
//
//
////        String mockData="{\"ord_no\":\"9167877864617048258756682\",\"ord_mct_id\":\"2212687054\",\"ord_shop_id\":\"2212687054\",\"ord_currency\":\"CNY\",\"currency_sign\":\"\\u00a5\",\"pmt_tag\":\"WeixinSN2\",\"pmt_name\":\"\\u5fae\\u4fe1\\u652f\\u4ed8\",\"trade_no\":\"4200059246202303148964150178\",\"trade_amount\":\"3441\",\"trade_qrcode\":null,\"trade_account\":\"oJNTV0ZGfP5SiBTSVwSgEfEA7HOs\",\"trade_result\":\"{\\\"return_code\\\":\\\"SUCCESS\\\",\\\"return_msg\\\":[],\\\"appid\\\":\\\"wxdb5f93f24605b962\\\",\\\"mch_id\\\":\\\"1513869461\\\",\\\"sub_mch_id\\\":\\\"544177450\\\",\\\"nonce_str\\\":\\\"b6bbcaa434484d418f6702204197df52\\\",\\\"result_code\\\":\\\"SUCCESS\\\",\\\"openid\\\":\\\"oJNTV0ZGfP5SiBTSVwSgEfEA7HOs\\\",\\\"trade_type\\\":\\\"MICROPAY\\\",\\\"bank_type\\\":\\\"OTHERS\\\",\\\"fee_type\\\":\\\"CNY\\\",\\\"total_fee\\\":\\\"3441\\\",\\\"cash_fee_type\\\":\\\"CNY\\\",\\\"cash_fee\\\":\\\"3441\\\",\\\"settlement_total_fee\\\":\\\"3441\\\",\\\"coupon_fee\\\":\\\"0\\\",\\\"transaction_id\\\":\\\"4200059246202303148964150178\\\",\\\"out_trade_no\\\":\\\"9167877864617048258756682\\\",\\\"attach\\\":\\\"bank_mch_name=\\\\u5c71\\\\u897f\\\\u9f99\\\\u5174\\\\u798f\\\\u5546\\\\u8d38\\\\u6709\\\\u9650\\\\u516c\\\\u53f8&bank_mch_id=860984435\\\",\\\"time_end\\\":\\\"20230314152407\\\",\\\"sign\\\":\\\"MEUCIFAke3hwbFZyY6VGE2rik8SfEw97DiscE7vIwvnMJ8jwAiEAh3FY24PKfOwSZlCyyqHX+lHxRmgZ+3buenDSB7W3QXg=\\\"}\",\"trade_pay_time\":\"2023-03-14 15:24:07\",\"trade_discout_amount\":\"0\",\"status\":\"1\",\"out_no\":\"29006616787786460314152403007766\",\"discount_amount\":\"0\"}";
////        String mockData = "{\"ord_id\":\"2147920950\",\"ord_no\":\"9167705372970938039330173\",\"ord_type\":\"2\",\"ord_mct_id\":\"2147920950\",\"ord_shop_id\":\"2147920950\",\"ord_name\":\"(\\u9000\\u6b3e)WeixinSN2-9167722296074920093963567\",\"add_time\":\"2023-02-24 15:20:19\",\"trade_account\":null,\"trade_amount\":\"2477\",\"trade_time\":\"2023-02-24 15:20:20\",\"trade_no\":\"4200057835202302229269928572\",\"trade_qrcode\":null,\"trade_pay_time\":\"2023-02-24 15:20:20\",\"remark\":null,\"status\":\"1\",\"original_amount\":\"12724\",\"discount_amount\":\"0\",\"ignore_amount\":\"0\",\"trade_discout_amount\":\"0\",\"original_ord_no\":\"9167722296074920093963567\",\"trade_result\":\"{\\\"return_code\\\":\\\"SUCCESS\\\",\\\"appid\\\":\\\"wxdb5f93f24605b962\\\",\\\"mch_id\\\":\\\"1513869461\\\",\\\"sub_mch_id\\\":\\\"544177450\\\",\\\"result_code\\\":\\\"SUCCESS\\\",\\\"nonce_str\\\":\\\"58242f74e91741d599237c5a7bd3ce8c\\\",\\\"out_refund_no\\\":\\\"9167705372970938039330173\\\",\\\"refund_id\\\":\\\"4200057835202302229269928572\\\",\\\"refund_fee\\\":\\\"2477\\\",\\\"cash_refund_fee\\\":\\\"2477\\\",\\\"coupon_refund_fee\\\":\\\"0\\\",\\\"sign\\\":\\\"MEUCIQD3eL6qWaX0vvadV3K0TQhhAVeoNDRSs5+SnFVBV3h2ZQIgBgVgPBtMZ1E\\\\\\/5D4EO6aEFXXXNI5hhLYOGxp8D5oZh80=\\\"}\",\"currency\":\"CNY\",\"currency_sign\":\"\\u00a5\",\"out_no\":\"29006616770537290222161502008366\",\"pmt_tag\":\"WeixinSN2\",\"pmt_name\":\"\\u5fae\\u4fe1\\u652f\\u4ed8\",\"tag\":null,\"scr_id\":\"0\",\"shop_no\":\"860984435\",\"tml_no\":\"0\",\"ord_trade_no2\":null,\"ord_credit_card\":\"1\"}";
//////        String result ="{\"data\":\"c0080d4ef28e1880ca350baded22fdb7ec7c27d4b70e24088014ae494d0c8b8ebefcf62aae5dd906f27103ce221897f1be6ae619b0aa1d1a4a12a6ba3223573b\",\"open_id\":\"42a8aef029208f0e027a49cd0d63fb28\",\"sign\":\"61cdffeb459bfef37f23d866cefcc307\",\"timestamp\":\"1678779292\"}";
//////        String result ="{\"data\":\"c0080d4ef28e1880ca350baded22fdb7f5bba86d9d3f11a00b7dd6a8c8f2ddebac2993304fdd8ea050f0d9ddaefb25fe3430b22253279dbdd683dd6f0325b185\",\"open_id\":\"42a8aef029208f0e027a49cd0d63fb28\",\"sign\":\"94073e4fa1d0704a717f20617a39a1c3\",\"timestamp\":\"1678795203\"}";
////        String result = rcbSign(mockData);
////        System.out.println(rcbDecrypt(result));

        String a= "";
        String b="test";
        if (a.contains(b)){
            System.out.println(true);
        }
    }

}
