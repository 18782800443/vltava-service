package core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import com.alibaba.jvm.sandbox.api.*;
import com.alibaba.jvm.sandbox.api.event.*;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatcher;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.dmall.monitor.sdk.Monitor;
import com.dmall.vltava.domain.enums.MockTypeEnum;
import com.dmall.vltava.domain.enums.TaskStatusEnum;
import com.dmall.vltava.domain.mock.InvokeVO;
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
import util.Json2Map;
import util.LogbackUtils;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Rob
 */
@MetaInfServices(Module.class)
@Information(id = "vltava-agent", version = "0.0.1", author = "Rob")
public class Core implements Module, ModuleLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(Core.class);
    private static final Logger paramLogger = LoggerFactory.getLogger("IN_OUT");
    private static final String RPC_CONTEXT_CLASS = "org.apache.dubbo.rpc.RpcContext";
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
        if (CORE_MAP.containsKey(reference)) {
            return true;
        }
        logger.info("##### reference is "+reference);
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
                                                            logger.info("event is "+JSONObject.toJSONString(event));
                                                            EventBO eventBO = new EventBO();
                                                            eventBO.setReference(coreBO.getReference());
                                                            try {
                                                                switch (event.type) {
                                                                    case BEFORE:
                                                                        BeforeEvent beforeEvent = (BeforeEvent) event;
                                                                        logger.info("##准备开始mock的前置处理！");
                                                                        mockBefore(beforeEvent, eventBO);
                                                                        break;
                                                                    case RETURN:
                                                                        ReturnEvent returnEvent = (ReturnEvent) event;
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

    private static void mockBefore(BeforeEvent beforeEvent, EventBO eventBO) throws ProcessControlException {
        logger.info("##本次处理的类是："+beforeEvent.javaClassName);
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
                return;
            }
            // 非入口
            Tracer.getContext().startInvoke(beforeEvent);
        }
        if (eventBO.getMatchedMockAction().getMockType().equals(MockTypeEnum.RETURN_VALUE.getKey())) {
            Class clz = null;
            try {
                clz = beforeEvent.javaClassLoader.loadClass(beforeEvent.javaClassName);
            } catch (ClassNotFoundException e) {
                logger.error(e.getMessage(), e);
            }
            for (Method method : clz.getMethods()) {
                if (method.getName().equals(beforeEvent.javaMethodName)) {
                    Object returnObj = null;
                    updateDynamic(eventBO, beforeEvent);
                    logger.info(String.format("@" + beforeEvent.invokeId + "@ " + "will return expect value: %s", eventBO.getMatchedMockAction().getExpectValue()));
                    if (JSON.parseObject(eventBO.getMatchedMockAction().getExpectValue()).containsKey("class")) {
                        returnObj = PojoUtils.realize(JSON.parseObject(eventBO.getMatchedMockAction().getExpectValue()), method.getReturnType(), method.getGenericReturnType());
                    } else {
                        returnObj = JSON.parseObject(eventBO.getMatchedMockAction().getExpectValue(), method.getGenericReturnType());
                    }
                    logger.info("即将返回的调用结果:"+ JSONObject.toJSONString(returnObj));
                    ProcessController.returnImmediately(returnObj);
                }
            }
        } else if (eventBO.getMatchedMockAction().getMockType().equals(MockTypeEnum.THROW_EXCEPTION.getKey())) {
            logger.info(String.format("@" + beforeEvent.invokeId + "@ " + "will throw Exception: %s", eventBO.getMatchedMockAction().getErrorMsg()));
            ProcessController.throwsImmediately(new Exception(eventBO.getMatchedMockAction().getErrorMsg()));
        }
    }

    private static void updateDynamic(EventBO eventBO, BeforeEvent beforeEvent){
        if (eventBO.getMatchedMockAction().getDynamicChange() != null && eventBO.getMatchedMockAction().getDynamicChange()){
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
            logger.info("替换后结果："+ eventBO.getMatchedMockAction().getExpectValue());
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

    /**
     * 匹配规则
     */
    private static void matchAction(BeforeEvent beforeEvent, EventBO eventBO, String params, String tid) {
        logger.info(" 正则匹配，匹配被调用类("+beforeEvent.javaClassName+")的mockKey");
        eventBO.setMatch(false);
        TraceContext traceContext = Tracer.getContext();
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
                        if(!params.contains(expect.trim())){
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
        try {
            logger.info(beforeEvent.javaClassName+" ##处理RPC的隐式参数...");
            Class rpcContext = beforeEvent.javaClassLoader.loadClass(RPC_CONTEXT_CLASS);
            Method getContext = rpcContext.getMethod("getContext");
            logger.info(getContext.invoke(rpcContext).toString());
            String contextStr = JSON.toJSONString(getContext.invoke(rpcContext));
//            logger.info("REF: " + eventBO.getReference() + " Context" + contextStr);
            if (contextStr.contains(VLTAVA_MOCK_KEY)) {
                Matcher matcher = PATTERN.matcher(contextStr);
                if (matcher.find()) {
                    String mockKey = matcher.group(0);
                    logger.info("@" + beforeEvent.invokeId + "@ " + "mockKey: " + mockKey);
                    eventBO.setMockKey(mockKey);
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }


}
