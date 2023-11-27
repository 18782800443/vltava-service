package core;

import com.testhuamou.vltava.domain.mock.TraceVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

/**
 * @author Rob
 */
public class MsgCenter {
    private static final Cache<String, TraceVO> TRACE_CACHE = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(100).build();

    public static void storeTraceVO(TraceVO traceVO){
        TRACE_CACHE.put(traceVO.getTid(), traceVO);
    }

    public static TraceVO getTraceVO(String traceId){
        return TRACE_CACHE.getIfPresent(traceId);
    }

}
