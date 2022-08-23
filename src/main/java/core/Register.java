package core;

import com.alibaba.fastjson.JSON;
import com.dmall.vltava.domain.mock.RegisterVO;
import http.HttpService;

import mq.MqService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.BindException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Rob
 */
public class Register {
    private static final Logger logger = LoggerFactory.getLogger(Register.class);

    private static final Integer PORT = 8088;
    private static final Integer OPTIONAL_PORT = 8089;

    private static Pattern pattern = Pattern.compile(".*?(?=-[gr]z\\d)");
    private static RegisterVO registerVO;

    public static HttpService register() {
        logger.info("开始初始化业务...");
        getDockerInfo();
        logger.info("registerInfo: " + JSON.toJSONString(registerVO));
        HttpService httpService = null;
        boolean success = startServer(httpService, PORT);
        if (!success) {
            success = startServer(httpService, OPTIONAL_PORT);
        }
        if (success) {
            logger.info("HTTP服务器启动成功!");
            logger.info("registerVO is "+JSON.toJSONString(registerVO));
            MqService.send(registerVO);
            return httpService;
        } else {
            throw new RuntimeException("端口均被占用，启动失败");
        }
    }

    public static Boolean isSameSystem(String systemUniqueName, String zone, String group) {
        return systemUniqueName.equals(registerVO.getSystemUniqueName()) && zone.equals(registerVO.getZone()) && group.equals(registerVO.getGroup());
    }

    // for debug
    public static Boolean isPromise(){
        return registerVO.getSystemUniqueName().equals("promise-dmall-api");
    }

    private static Boolean startServer(HttpService httpService, Integer port) {
//        logger.info("测试报错是否影响启动");
//        throw new RuntimeException("测试报错是否影响启动");
        try {
            httpService = new HttpService(registerVO.getIp(), port);
            httpService.start(10000, false);
            registerVO.setPort(port);
            return true;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            if (e instanceof BindException) {
                logger.error(port + "被占用，尝试试用备选端口");
            }
            return false;
        }
    }

    private static void getDockerInfo() {
        registerVO = new RegisterVO();
        getSystemUniqueInfo();
        getServerIp();
        registerVO.setTime(System.currentTimeMillis());
    }

    private static void getSystemUniqueInfo() {
        String[] command = new String[]{"hostname"};
        String[] debugCommand = new String[]{"sh", "-c", "ifconfig eth0 | grep \"inet\"|awk '{print $2}'"};
        String debugResp = executeShell(debugCommand);
        if (debugResp.contains("10.16.244.61") || debugResp.contains("10.16.244.14")) {
            // idc
            registerVO.setSystemUniqueName("promiseIDC");
            registerVO.setZone("idc");
            registerVO.setGroup("idc");
        } else {
            // docker
            String result = executeShell(command);
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                String systemName = matcher.group();
                String zone = result.substring(systemName.length() + 1).split("-")[0];
                String group = result.substring(systemName.length() + 1).split("-")[1];
                registerVO.setSystemUniqueName(systemName);
                registerVO.setZone(zone);
                registerVO.setGroup(group);
            } else {
                logger.error("获取hostname失败！");
                throw new RuntimeException();
            }
        }
    }

    private static void getServerIp() {
        String[] command = new String[]{"sh", "-c", "ifconfig eth0 | grep \"inet \"|awk '{print $2}'"};
//        String[] command = new String[]{"sh", "-c", "ifconfig en4 | grep \"inet\"|awk '{print $2}'"};
        registerVO.setIp(executeShell(command));
    }

    private static String executeShell(String[] command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            InputStream inputStream = process.getInputStream();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String result = null;
            String tmp = null;
            while ((tmp = bufferedReader.readLine()) != null) {
                result = tmp != null ? tmp : result;
                logger.info(result);
            }
            bufferedReader.close();
            inputStream.close();
            process.destroy();
            return result;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return null;
    }

}
