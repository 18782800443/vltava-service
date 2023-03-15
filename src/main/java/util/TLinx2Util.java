package util;

import com.alibaba.fastjson.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;


public class TLinx2Util {
    private final static String OPEN_KEY = "open_key";
    private final static String SIGN = "sign";


    public static String sign(Map<String, String> postMap) {
        String sign = null;
        try {
            // A~z排序(加上open_key)
            String sortStr = sort(postMap);
            //sha1加密(小写)
            String sha1 = TLinxSHA1.SHA1(sortStr).toLowerCase();
            //3 md5加密(小写)
            sign = TLinxMD5.MD5Encode(sha1).toLowerCase();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sign;
    }


    public static Boolean verifySign(JSONObject respObject, String openKey) {
        String respSign = respObject.get(SIGN).toString();
        respObject.remove(SIGN);    // 删除sign节点
        respObject.put(OPEN_KEY, openKey);
        // 按A~z排序，串联成字符串，先进行sha1加密(小写)，再进行md5加密(小写)，得到签名
        String veriSign = sign(JSONObject.toJavaObject(respObject, Map.class));
        if (respSign.equals(veriSign)) {
            return true;
        }
        return false;
    }


    public static String handleEncrypt(String body, String openKey) throws Exception {
        return TLinxAESCoder.encrypt(body, openKey);    // AES加密，并bin2hex
    }


    public static void handleSign(TreeMap<String, String> postMap, String openKey) {
        Map<String, String> veriDataMap = new HashMap<String, String>();
        veriDataMap.putAll(postMap);
        veriDataMap.put(OPEN_KEY, openKey);
        // 签名
        String sign = sign(veriDataMap);
        postMap.put(SIGN, sign);
    }


    public static byte[] hex2byte(String strhex) {
        if (strhex == null) {
            return null;
        }
        int l = strhex.length();
        if (l % 2 == 1) {
            return null;
        }
        byte[] b = new byte[l / 2];
        for (int i = 0; i != l / 2; ++i)
            b[i] = (byte) Integer.parseInt(strhex.substring(i * 2, i * 2 + 2), 16);
        return b;
    }


    public static String byte2hex(byte[] result) {
        StringBuffer sb = new StringBuffer(result.length * 2);
        for (int i = 0; i < result.length; i++) {
            int hight = ((result[i] >> 4) & 0x0f);
            int low = result[i] & 0x0f;
            sb.append(hight > 9 ? (char) ((hight - 10) + 'a') : (char) (hight + '0'));
            sb.append(low > 9 ? (char) ((low - 10) + 'a') : (char) (low + '0'));
        }
        return sb.toString();
    }


    public static String sort(Map paramMap) throws Exception {
        String sort = "";
        TLinxMapUtil signMap = new TLinxMapUtil();
        if (paramMap != null) {
            String key;
            for (Iterator it = paramMap.keySet().iterator(); it.hasNext(); ) {
                key = (String) it.next();
                String value = ((paramMap.get(key) != null) && (!("".equals(paramMap.get(key).toString())))) ? paramMap.get(key).toString() : "";
                signMap.put(key, value);
            }
            signMap.sort();
            for (Iterator it = signMap.keySet().iterator(); it.hasNext(); ) {
                key = (String) it.next();
                sort = sort + key + "=" + signMap.get(key).toString() + "&";
            }
            if ((sort != null) && (!("".equals(sort)))) {
                sort = sort.substring(0, sort.length() - 1);
            }
        }
        return sort;
    }
}


