package core.trace;

import com.alibaba.fastjson.JSON;
import com.alibaba.ttl.TransmittableThreadLocal;
import core.Core;
import core.DataCenter;
import core.domain.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

/**
 * @author Rob
 */
public class Tracer {
    private static final Logger logger = LoggerFactory.getLogger(Core.class);
    private static final ThreadLocal<TraceContext> THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<TraceContext> TRANS_THREAD_LOCAL = new TransmittableThreadLocal<>();

    public static TraceContext start(String mockKey, String entranceReference, String tid){
        TraceContext context = getContextCarrie().get();
        if (context != null){
            return context;
        }
        context = new TraceContext(mockKey, entranceReference, tid);
        logger.info("[TRACE] start trace TID:" + tid);
        getContextCarrie().set(context);
        return context;
    }

    public static void setMockKey(String mockKey){
        TraceContext context = getContextCarrie().get();
        if (context != null){
            context.setMockKey(mockKey);
        }
        logger.info("[CONTEXT 0 ] " + JSON.toJSONString(context));
        getContextCarrie().set(context);
        logger.info("[CONTEXT 1 ] " + JSON.toJSONString(getContextCarrie().get()));

    }


    public static TraceContext getContext(){
        return getContextCarrie().get();
    }

    public static void  setContext(HashMap mockMap){

    }

    public static String getTid(){
        return getContextCarrie().get().getTid();
    }

    public static void end(){
        final TraceContext context = getContext();
        if (context != null){
            logger.info("[TRACE] stop trace TID:" + context.getTid());
        }
        getContextCarrie().remove();
    }

    private static ThreadLocal<TraceContext> getContextCarrie(){
        return DataCenter.getConcurrent() ? TRANS_THREAD_LOCAL: THREAD_LOCAL;
    }



}
