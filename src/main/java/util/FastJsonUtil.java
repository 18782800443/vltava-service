package util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializeFilter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import com.testhuamou.fit.boot.spec.jackson.utils.JSONUtil;


public class FastJsonUtil {
    public final static String JSON_ERROR = "JSON Not allowed to be empty";
    //Default serializer property
    private final static SerializerFeature DEFAULT_SERIAL = SerializerFeature.WriteMapNullValue;

    private static final SerializerFeature[] DEFAULT_SERIALIZER_FEATURE = new SerializerFeature[]{
            SerializerFeature.WriteMapNullValue,//输出为null的字段
            SerializerFeature.WriteDateUseDateFormat,//使用时间格式化
    };

    //Private constructor,Not expose to outer
    private FastJsonUtil() {

    }

    /**
     * Parse JSON string to java Object,the pojo was second param decided
     *
     * @param jsonStr JSON character
     * @param classes java Object class
     * @param <T>     POJO
     * @return java POJO
     */
    public static <T> T parseObject(String jsonStr, Class<T> classes) {
        Assert.notNull(jsonStr, JSON_ERROR);
        return JSONUtil.parseObject(jsonStr, classes);
    }

    /**
     * Parse JSON string to collection,the pojo in collection was second param decided
     *
     * @param jsonStr JSON character
     * @param classes java Object class
     * @param <T>     POJO
     * @return list collection
     */
    public static <T> List<T> parseArray(String jsonStr, Class<T> classes) {
        Assert.notNull(jsonStr, JSON_ERROR);
        return JSONUtil.parseArray(jsonStr, classes);
    }


    /**
     * Write java pojo to string,and could custom serializer property
     *
     * @param origin   JAVA POJO
     * @param features serializer property
     * @return JSON character
     */
    public static String writeObjectAsString(Object origin, SerializerFeature... features) {
        List<SerializeFilter> filters = new ArrayList<>();
        return writeObjectAsString(origin, filters, features);
    }

    public static String writeObjectAsString(Object origin, List<SerializeFilter> filters, SerializerFeature... features) {
        Assert.notNull(origin, "JAVA pojo Not allowed to be empty ");
        String writeValue = null;
        if (CollectionUtils.isEmpty(filters)) {
            filters = new ArrayList<>();
        }
        if (features == null || features.length == 0) {
            writeValue = JSON.toJSONString(origin, filters.toArray(new SerializeFilter[filters.size()]), DEFAULT_SERIAL);
        } else {
            writeValue = JSON.toJSONString(origin, filters.toArray(new SerializeFilter[filters.size()]), features);
        }
        return writeValue;
    }

    public static String writeObjectAsString(Object object, SerializeFilter[] serializeFilters) {
        SerializerFeature[] serializerFeatures = Arrays
                .copyOf(DEFAULT_SERIALIZER_FEATURE, DEFAULT_SERIALIZER_FEATURE.length + 1);
        serializerFeatures[serializerFeatures.length - 1] = SerializerFeature.DisableCircularReferenceDetect;
        return JSONObject.toJSONString(object, serializeFilters, serializerFeatures);
    }

    public static String writeObjectAsStringEnableCircularReferenceDetect(Object object, SerializeFilter[] serializeFilters) {
        return JSONObject.toJSONString(object, serializeFilters, DEFAULT_SERIALIZER_FEATURE);
    }
}
