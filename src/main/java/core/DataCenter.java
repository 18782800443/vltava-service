package core;

import com.alibaba.fastjson.JSON;
import com.dmall.vltava.domain.agent.InjectResult;
import com.dmall.vltava.domain.enums.InjectStatusEnum;
import com.dmall.vltava.domain.enums.TaskStatusEnum;
import com.dmall.vltava.domain.mock.MockActionVO;
import com.dmall.vltava.domain.mock.MockVO;
import com.dmall.vltava.domain.mock.TraceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author Rob
 */
public class DataCenter {
    private static final Logger logger = LoggerFactory.getLogger(DataCenter.class);

    /**
     * 存放初始数据 key = mockKey
     */
    private static Map<String, MockVO> mockVOMap = new HashMap<>();
    /**
     * 存放整理后数据 key = reference
     */
    private static Map<String, List<MockActionVO>> mockActionVOMap = new HashMap<>();
    /**
     * 存放整理后reference状态
     */
    private static Map<String, Boolean> referenceStatusMap = new HashMap<>();

    private static Boolean concurrent = true;

    public static Boolean getConcurrent() {
        return concurrent;
    }

    public static void setConcurrent(Boolean concurrent) {
        DataCenter.concurrent = concurrent;
    }

    public static Boolean isStart(String reference) {
        return referenceStatusMap.get(reference) == null ? false: referenceStatusMap.get(reference);
    }

    public static List<MockActionVO> getActions(String reference) {
        return mockActionVOMap.get(reference);
    }

    public static String getReference(MockActionVO mockActionVO) {
        return mockActionVO.getClassName() + "#" + mockActionVO.getMethodName();
    }

    public static MockVO getMockVO(String mockKey) {
        return mockVOMap.get(mockKey);
    }

    public static void setMockVO(MockVO mockVO) {
        mockVOMap.put(mockVO.getMockKey(), mockVO);
    }

    public static void updateStatus(MockVO mockVO) {
        if (!needUpdate(mockVO)) {
            return;
        }
        MockVO exist = mockVOMap.get(mockVO.getMockKey());
        exist.setTaskStatus(mockVO.getTaskStatus());
        List<String> referenceList = new ArrayList<>();
        for (MockActionVO mockActionVO : exist.getMockActionList()) {
            mockActionVO.setTaskStatus(mockVO.getTaskStatus());
            String reference = getReference(mockActionVO);
            referenceList.add(reference);
            for (MockActionVO actionVO : mockActionVOMap.get(reference)) {
                if (actionVO.getMockKey().equals(mockVO.getMockKey())){
                    actionVO.setTaskStatus(mockVO.getTaskStatus());
                }
            }
        }
        tidyReferenceMap(referenceList);
        updateReference(referenceList);
        logger.info("referenceMap " + JSON.toJSONString(referenceStatusMap));
    }

    public static InjectResult updateData(List<MockVO> mockVOList) {
        List<String> referenceList = new ArrayList<>(referenceStatusMap.keySet());
        for (MockVO mockVO : mockVOList) {
            if (!needUpdate(mockVO)) {
                break;
            }
            logger.info("mockVO: " + JSON.toJSONString(mockVO));
            //检查方法是否存在
            List<MockActionVO> failList = preCheck(mockVO);
            if (failList.size() > 0) {
                logger.warn("预先检测-部分接口未注入成功："+ JSON.toJSONString(failList));
                mockVOMap.clear();
                return new InjectResult(InjectStatusEnum.PART.getKey(), "部分接口未能成功注入，请重试或检查", failList);
            }
            mockVOMap.put(mockVO.getMockKey(), mockVO);
            for (MockActionVO mockActionVO : mockVO.getMockActionList()) {
                mockActionVO.setId(mockVO.getId());
                String reference = getReference(mockActionVO);
                if (!referenceList.contains(reference)){
                    referenceList.add(reference);
                }
                if (!mockActionVOMap.containsKey(reference)) {
                    mockActionVOMap.put(reference, new ArrayList<MockActionVO>());
                }
                boolean add = false;
                List<MockActionVO> existList = mockActionVOMap.get(reference);
                for (int i = 0; i < existList.size(); i++) {
                    if (existList.get(i).getUid().equals(mockActionVO.getUid())) {
                        existList.set(i, mockActionVO);
                        add = true;
                        break;
                    }
                }
                if (!add) {
                    existList.add(mockActionVO);
                }
            }
        }
        tidyActionMap(mockVOList);
        tidyReferenceMap(referenceList);
        logger.info("actionMap " + JSON.toJSONString(mockActionVOMap));
        logger.info("referenceMap " + JSON.toJSONString(referenceStatusMap));
        List<MockActionVO> failResult = updateReference(referenceList);
        if (failResult.size() == 0) {
            return new InjectResult(InjectStatusEnum.SUCCESS.getKey());
        } else if (referenceList.size() > failResult.size()) {
            mockVOMap.clear();
            return new InjectResult(InjectStatusEnum.PART.getKey(), "部分接口未能成功注入，请重试或检查", failResult);
        } else {
            mockVOMap.clear();
            return new InjectResult(InjectStatusEnum.FAIL.getKey(), "注入失败");
        }
    }

    private static List<MockActionVO> preCheck(MockVO mockVO){
        List<String> referenceList = new ArrayList<>();
        List<MockActionVO> failList = new ArrayList<>();
        for (MockActionVO mockActionVO : mockVO.getMockActionList()) {
            String reference = getReference(mockActionVO);
            if (!referenceStatusMap.containsKey(reference)){
                referenceList.add(reference);
            }
        }
        if (referenceList.size()>0){
            for (String reference : referenceList) {
                if (!Core.start(reference)){
                    MockActionVO mockActionVO = new MockActionVO();
                    mockActionVO.setClassName(reference.split("#")[0]);
                    mockActionVO.setMethodName(reference.split("#")[1]);
                    failList.add(mockActionVO);
                }
                Core.close(reference);
            }
        }
       return failList;
    }

    private static List<MockActionVO> updateReference(List<String> referenceList) {
        List<MockActionVO> failResult = new ArrayList<>();
        for (String reference : referenceList) {
            if (!isStart(reference)) {
                Core.close(reference);
            } else {
                Boolean b = Core.start(reference);
                if (!b) {
                    MockActionVO cls = new MockActionVO();
                    cls.setClassName(reference.split("#")[0]);
                    cls.setMethodName(reference.split("#")[1]);
                    failResult.add(cls);
                }
            }
        }
        return failResult;
    }

    /**
     * 清理删除的actions
     * @param mockVOList
     */
    private static void tidyActionMap(List<MockVO> mockVOList) {
        for (String key : mockActionVOMap.keySet()) {
            Iterator<MockActionVO> iter = mockActionVOMap.get(key).iterator();
            while (iter.hasNext()) {
                MockActionVO existVO = iter.next();
                boolean match = true;
                out:
                for (MockVO mockVO : mockVOList) {
                    if (mockVO.getId().equals(existVO.getId())) {
                        match = false;
                        for (MockActionVO newActionVO : mockVO.getMockActionList()) {
                            if (newActionVO.getUid().equals(existVO.getUid())) {
                                match = true;
                                break out;
                            }
                        }
                    }
                }
                if (!match) {
                    iter.remove();
                }
            }
        }
    }

    /**
     * 刷新reference状态
     */
    private static void tidyReferenceMap(List<String> referenceList) {
        for (String reference : referenceList) {
            boolean status = false;
            for (MockActionVO mockActionVO : mockActionVOMap.get(reference)) {
                if (mockActionVO.getTaskStatus().equals(TaskStatusEnum.RUNNING.getKey())) {
                    status = true;
                    break;
                }
            }
            referenceStatusMap.put(reference, status);
        }
    }

    private static boolean needUpdate(MockVO mockVO) {
        logger.info("mockVOMap:" + JSON.toJSONString(mockVOMap));
        if (!mockVOMap.containsKey(mockVO.getMockKey())) {
            return true;
        } else {
            logger.info(String.format("exist version %s , new version %s",mockVOMap.get(mockVO.getMockKey()).getVersion(), mockVO.getVersion() ));
            return mockVO.getVersion() > mockVOMap.get(mockVO.getMockKey()).getVersion();
        }
    }

}
