package mq;

import com.alibaba.fastjson.JSON;
import com.dmall.dmg.sdk4.comm.auth.AuthInfo;
import com.dmall.dmg.sdk4.comm.exception.DMGSendException;
import com.dmall.dmg.sdk4.rocketmq.message.RocketMsg;
import com.dmall.dmg.sdk4.rocketmq.producer.DefRocketProducerWrapper;
import com.dmall.dmg.sdk4.rocketmq.producer.RocketCallback;
import com.dmall.monitor.sdk.MonitorConfig;
import com.dmall.vltava.domain.mock.RegisterVO;
import core.Core;

import org.apache.rocketmq.client.producer.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * @author Rob
 */
public class MqService {
    private static final Logger logger = LoggerFactory.getLogger(MqService.class);

    private final static String PROJECT_CODE = "aladdin-FIT";
    private final static String APP_CODE = "dmall-fit-vltava";
    private final static String SECRET_KEY = "5795DBAF-1888-47AA-89B2-CCD224BE65E9";
    private final static String DMG_SERVER_ADDR = "testamp.dmg.api.fit.inner-dmall.com";
    private final static String DMC_SERVER_ADDR  = "testds.dmc.api.fit.inner-dmall.com";
    private final static String TOPIC = "rkt_vltava_req_test_test";

    private static DefRocketProducerWrapper wrapper;

    static {
        initProducer();
    }

    public static void send(RegisterVO registerVO) {
        try {
            RocketMsg rocketMsg = new RocketMsg(TOPIC, JSON.toJSONString(registerVO));
            wrapper.sendAsync(rocketMsg, new RocketCallback(){
                @Override
                public void onError(Throwable throwable) {
                    logger.info("注册MQ发送失败！");
                    logger.error(throwable.getMessage(),throwable);
                }

                @Override
                public void onSucc(SendResult sendResult) {
                    logger.info("注册MQ发送成功！");
                    logger.info("messageId is "+sendResult.getMsgId());
                }
            });
        } catch (DMGSendException e) {
            e.printStackTrace();
        }
    }

    private static void initProducer() {
        MonitorConfig monitorConfig = new MonitorConfig(PROJECT_CODE, APP_CODE, DMC_SERVER_ADDR, true);
        monitorConfig.monitorInit();

        AuthInfo authInfo = new AuthInfo(SECRET_KEY, DMG_SERVER_ADDR, 3000);

        Set<String> topics = new HashSet<>();
        topics.add(TOPIC);

        wrapper = new DefRocketProducerWrapper();
        wrapper.setTopics(topics);
        wrapper.setAuthInfo(authInfo);
        wrapper.doInit();
    }


}
