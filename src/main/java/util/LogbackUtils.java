package util;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import com.alibaba.fastjson.JSON;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URISyntaxException;

/**
 * Logback日志框架工具类
 */
public class LogbackUtils {


    /**
     * 初始化Logback日志框架
     *
     */
    public static void init() {

        File configureFile = new File("tmp.xml");
        InputStream is = LogbackUtils.class.getClassLoader().getResourceAsStream("logback.xml");
        int len = 0;
        byte[] buffer = new byte[8192];
        try {
            OutputStream os = new FileOutputStream(configureFile);
            while ((len = is.read(buffer)) != -1){
                os.write(buffer,0, len);
            }
            os.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final JoranConfigurator configurator = new JoranConfigurator();

        configurator.setContext(loggerContext);
        loggerContext.reset();
        final Logger logger = LoggerFactory.getLogger(LoggerFactory.class);
        is = LogbackUtils.class.getClassLoader().getResourceAsStream("logback.xml");

        try {
            configurator.doConfigure(is);
            logger.info("initializing logback success. file={};", configureFile);
            is.close();
        } catch (Throwable cause) {

            logger.warn("initialize logback failed. file={};", configureFile, cause);
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    /**
     * 销毁Logback日志框架
     */
    public static void destroy() {
        try {
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        } catch (Throwable cause) {
            cause.printStackTrace();
        }
    }

}
