package core.progress;

import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.dmall.vltava.domain.base.CommonException;
import core.Core;
import core.domain.CoreBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Rob
 */
public class VltavaProgress implements ModuleEventWatcher.Progress {
    private static final Logger logger = LoggerFactory.getLogger(VltavaProgress.class);
    private CoreBO coreBO;

    public VltavaProgress(CoreBO coreBO) {
        this.coreBO = coreBO;
    }

    @Override
    public void begin(int i) {
        logger.info("开始注入: " + coreBO.getReference());
    }

    @Override
    public void progressOnSuccess(Class<?> aClass, int i) {
        coreBO.setFinish(true);
        coreBO.setWatching(true);
        logger.info("注入成功! " + coreBO.getReference());
    }

    @Override
    public void progressOnFailed(Class<?> aClass, int i, Throwable throwable) {
        coreBO.setFinish(true);
        coreBO.setWatching(false);
        coreBO.getEventWatcher().onUnWatched();
        throw new CommonException(throwable.getMessage(), throwable);
    }

    @Override
    public void finish(int i, int i1) {
        logger.info(String.format("%s 注入完成，cCnt: %s mCnt: %s", coreBO.getReference(), i, i1));
        if (i == 0 || i1 ==0){
            logger.error("未找到对应方法");
            coreBO.setFinish(true);
            coreBO.setWatching(false);
            if (coreBO.getEventWatcher() != null){
                coreBO.getEventWatcher().onUnWatched();
                logger.info("流程管理-卸载完成 " + coreBO.getReference());
            } else {
                logger.info("流程管理-未卸载完成 " + coreBO.getReference());
            }
            Core.close(coreBO);
            throw new CommonException("未找到对应方法");
        } else {
            coreBO.setFinish(true);
            coreBO.setWatching(true);
            logger.info("注入成功!!");
        }
    }
}
