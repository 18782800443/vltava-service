package core.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.jvm.sandbox.api.event.BeforeEvent;
import com.alibaba.jvm.sandbox.api.event.ReturnEvent;
import com.alibaba.jvm.sandbox.api.event.ThrowsEvent;
import com.dmall.vltava.domain.mock.InvokeVO;
import com.dmall.vltava.domain.mock.TraceVO;
import core.DataCenter;
import org.apache.dubbo.common.utils.PojoUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Rob
 */
public class TraceContext {
    String mockKey;
    String entranceReference;
    String tid;
    TraceVO traceVO;
    Map<Integer, InvokeVO> invokeMap;

    public TraceContext(String mockKey, String entranceReference, String tid) {
        this.mockKey = mockKey;
        this.entranceReference = entranceReference;
        this.tid = tid;
        this.traceVO = new TraceVO(tid);
        this.invokeMap = DataCenter.getConcurrent() ? new ConcurrentHashMap<>() : new HashMap<>();
    }

    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public String getMockKey() {
        return mockKey;
    }

    public void setMockKey(String mockKey) {
        this.mockKey = mockKey;
    }

    public String getEntranceReference() {
        return entranceReference;
    }

    public void setEntranceReference(String entranceReference) {
        this.entranceReference = entranceReference;
    }

    public TraceVO getTraceVO() {
        return traceVO;
    }

    public void setTraceVO(TraceVO traceVO) {
        this.traceVO = traceVO;
    }

    public void startInvoke(BeforeEvent event){
        InvokeVO cls  = new InvokeVO(event.javaClassName, event.javaMethodName, JSON.toJSONString(PojoUtils.generalize(event.argumentArray)));
        invokeMap.put(event.invokeId, cls);
    }

    public void finishInvoke(ReturnEvent event) {
        InvokeVO cls = invokeMap.get(event.invokeId);
        cls.endWithResp(JSON.toJSONString(PojoUtils.generalize(event.object)));
        this.traceVO.getInvokeList().add(cls);
        invokeMap.remove(event.invokeId);
    }

    public void finishInvoke(ThrowsEvent event){
        InvokeVO cls = invokeMap.get(event.invokeId);
        cls.endWithError(event.throwable);
        this.traceVO.getInvokeList().add(cls);
        invokeMap.remove(event.invokeId);
    }
}
