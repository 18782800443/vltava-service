package core.listener;


import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import core.Core;
import core.DataCenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Rob
 */
public class VltavaEventListener implements EventListener {
    private static final Logger logger = LoggerFactory.getLogger(VltavaEventListener.class);

    public VltavaEventListener() {
    }

    /**
     * 基础跳过规则
     */
    @Override
    public void onEvent(Event event) throws Throwable {
        logger.info("new msg incoming...");
    }
}
