package core.trace;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor
public class RcbOpenKey {

    /**
     * 进件后生成的openId
     */
    @JSONField(serialize = false)
    private String openId;

    /**
     * 进件后生成的openKey
     */
    @JSONField(serialize = false)
    private String openKey;

    public RcbOpenKey(String openId, String openKey) {
        this.openId = openId;
        this.openKey = openKey;
    }
}
