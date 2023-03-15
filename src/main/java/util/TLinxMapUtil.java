/**
 * @Filename: TLinxMapUtil.java
 * @Author：caiqf
 * @Date�?014-10-11
 */
package util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TLinxMapUtil {
    private Map map = new HashMap();
    private Set keySet = map.keySet();

    public Object get(String key) {
        return map.get(key);
    }

    public void put(String key, Object value) {
        map.put(key, value);
    }

    public void sort() {
        List list = new ArrayList(map.keySet());

//		Collections.sort(list, new Comparator() {
//			public int compare(Object a, Object b) {
//				return a.toString().compareTo(b.toString());
//			}
//		});

        this.keySet = new TreeSet(list);
    }

    public Set keySet() {
        return this.keySet;
    }


}
