package core.domain;

import com.alibaba.jvm.sandbox.api.listener.ext.EventWatcher;
import com.dmall.vltava.domain.mock.MockActionVO;

/**
 * @author Rob
 */
public class CoreBO {
    private String reference;
    private String className;
    private String methodName;
    private EventWatcher eventWatcher;
    private Boolean finish;
    private Boolean watching;

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public EventWatcher getEventWatcher() {
        return eventWatcher;
    }

    public void setEventWatcher(EventWatcher eventWatcher) {
        this.eventWatcher = eventWatcher;
    }

    public Boolean getFinish() {
        return finish;
    }

    public void setFinish(Boolean finish) {
        this.finish = finish;
    }

    public Boolean getWatching() {
        return watching;
    }

    public void setWatching(Boolean watching) {
        this.watching = watching;
    }
}
