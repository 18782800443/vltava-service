package http;

import com.alibaba.fastjson.JSON;
import com.testhuamou.vltava.domain.agent.InjectResult;
import com.testhuamou.vltava.domain.base.CommonException;
import com.testhuamou.vltava.domain.enums.InjectStatusEnum;
import com.testhuamou.vltava.domain.mock.MockActionVO;
import com.testhuamou.vltava.domain.mock.MockVO;
import core.Core;
import core.DataCenter;
import core.Register;
import fi.iki.elonen.NanoHTTPD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Rob
 */
public class HttpService extends NanoHTTPD {
    private static final Logger logger = LoggerFactory.getLogger(HttpService.class);

    public HttpService(String hostname, int port) {
        super(hostname, port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        logger.info("receive a new message ");
        try {
            switch (session.getUri()) {
                case "/vltava-agent/updateData":
                    return updateData(session);
                case "/vltava-agent/updateStatus":
                    return updateStatus(session);
                case "/vltava-agent/health":
                    return genResp(InjectStatusEnum.SUCCESS.getKey());
                default:
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html", "404 无效路径");
            }
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
            return genResp(InjectStatusEnum.FAIL.getKey(), e.getMessage());
        }
    }

//    /**
//     * 新的reference,需新建watcher
//     */
//    private Response uploadNew(IHTTPSession session){
//        String data = getPostData(session);
//        logger.info("path -> uploadNew,  Data: " + data);
//        MockVO mockVO = JSON.parseObject(data, MockVO.class);
//        if (Register.isSameSystem(mockVO.getAppVo().getSystemUniqueName(), mockVO.getAppVo().getZone())){
//            try {
//                DataCenter.setMockVO(mockVO);
//                if (Core.start(mockVO.getId())){
//                    return newFixedLengthResponse(InjectStatusEnum.SUCCESS.getKey());
//                } else{
//                    return newFixedLengthResponse(InjectStatusEnum.FAIL.getKey());
//                }
//            } catch (Throwable e) {
//                logger.error(e.getMessage(), e);
//                return newFixedLengthResponse(e.getMessage());
//            }
//        } else {
//            logger.error("当前服务与传入参数不同，请稍后尝试");
//        }
//        return newFixedLengthResponse("当前服务与传入参数不同，请稍后尝试");
//    }

    /**
     * 纯数据变更
     */
    private Response updateData(IHTTPSession session) {
        String data = getPostData(session);
        logger.info("path -> updateData,  Data: " + data);
        List<MockVO> mockVOList = JSON.parseArray(data, MockVO.class);
        logger.info("parseResult: " + JSON.toJSONString(mockVOList));
        if (!mockVOList.removeIf(mockVO -> {
            return !Register.isSameSystem(mockVO.getAppVo().getSystemUniqueName(), mockVO.getAppVo().getZone(), mockVO.getAppVo().getBuildGroup());
        })) {
            try {
                return genResp(DataCenter.updateData(mockVOList));
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
                return genResp(InjectStatusEnum.FAIL.getKey(), e.getMessage());
            }
        } else {
            logger.error("当前服务与传入参数不同，请稍后尝试");
        }
        return genResp(InjectStatusEnum.FAIL.getKey(), "当前服务与传入参数不同，请稍后尝试");
    }

    /**
     * 纯状态变更
     */
    private Response updateStatus(IHTTPSession session) {
        String data = getPostData(session);
        logger.info("path -> updateStatus,  Data: " + data);
        MockVO mockVO = JSON.parseObject(data, MockVO.class);
        if (Register.isSameSystem(mockVO.getAppVo().getSystemUniqueName(), mockVO.getAppVo().getZone(), mockVO.getAppVo().getBuildGroup())) {
            try {
                DataCenter.updateStatus(mockVO);
                return genResp(InjectStatusEnum.SUCCESS.getKey());
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
                return genResp(InjectStatusEnum.FAIL.getKey(), e.getMessage());
            }
        } else {
            logger.error("当前服务与传入参数不同，请稍后尝试");
        }
        return genResp(InjectStatusEnum.FAIL.getKey(), "当前服务与传入参数不同，请稍后尝试");
    }

    private String getPostData(IHTTPSession session) {
        Map<String, String> map = new HashMap<>();
        try {
            session.parseBody(map);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return map.get("postData");
    }

    private NanoHTTPD.Response genResp(InjectResult injectResult) {
        String resp = JSON.toJSONString(injectResult);
        logger.info(resp);
        return newFixedLengthResponse(resp);
    }

    private NanoHTTPD.Response genResp(String enumKey, String msg, List<MockActionVO> mockActionVOList) {
        String resp = JSON.toJSONString(new InjectResult(enumKey, msg, mockActionVOList));
        logger.info(resp);
        return newFixedLengthResponse(resp);
    }

    private NanoHTTPD.Response genResp(String enumKey, String msg) {
        String resp = JSON.toJSONString(new InjectResult(enumKey, msg));
        logger.info(resp);
        return newFixedLengthResponse(resp);
    }

    private NanoHTTPD.Response genResp(String enumKey) {
        String resp = JSON.toJSONString(new InjectResult(enumKey));
        logger.info(resp);
        return newFixedLengthResponse(resp);
    }

}
