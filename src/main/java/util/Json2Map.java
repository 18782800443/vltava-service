package util;

import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;

import java.util.*;

public class Json2Map {
    public static Map<String, Object> json2Map(String jsonStr) {
            Map<String, Object> map = new HashMap<>();
            if (StringUtils.isNotEmpty(jsonStr)) {
                //jsonStr = Json2Map.replaceBlank(jsonStr);
                //最外层解析
                JSONObject json = JSONObject.fromObject(jsonStr);
                for (Object k : json.keySet()) {
                    Object v = json.get(k);
                    if (JSONNull.getInstance().equals(v)) {
                        map.put(k.toString(), "");
                        continue;
                    }
                    //如果内层还是数组的话，继续解析
                    if (v instanceof JSONArray) {
                        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
                        Iterator<JSONObject> it = ((JSONArray) v).iterator();
                        while (it.hasNext()) {
                            Object obj = it.next();
                            if (obj == null || JSONNull.getInstance().equals(obj)) {
                                continue;
                            }
                            JSONObject json2 = (JSONObject) obj;
                            if (null == json2 || json2.isNullObject() || json2.isEmpty()) {
                                continue;
                            } else {
                                list.add(json2Map(json2.toString()));
                            }
                        }
                        map.put(k.toString(), list);
                    } else {
                        map.put(k.toString(), v);
                    }
                }
                return map;
            } else {
                return null;
            }
    }
}
