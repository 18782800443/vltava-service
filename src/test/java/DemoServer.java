import core.Core;
import http.HttpService;

import java.io.IOException;

/**
 * @author Rob
 * @date Create in 6:30 下午 2020/7/27
 */
public class DemoServer {
    public static void main(String[] args) {
        HttpService httpService = new HttpService("127.0.0.1",28028);
        try {
            httpService.start();
            while (true){
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
