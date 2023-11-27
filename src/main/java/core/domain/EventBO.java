package core.domain;

import com.testhuamou.vltava.domain.mock.MockActionVO;

import java.util.HashMap;
import java.util.UUID;

/**
 * @author Rob
 */
public class EventBO {
    private Boolean match;
    private String mockKey;
    private MockActionVO matchedMockAction;
    private String reference;
    //小程序单，则需要自动回调支付系统的支付完成接口
    private String expectKey;
    private HashMap<String, Object> xcxPayParams;
    private Object responseObject;
    private Object[] requestObject;

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public Boolean getMatch() {
        return match;
    }

    public void setMatch(Boolean match) {
        this.match = match;
    }

    public String getMockKey() {
        return mockKey;
    }

    public void setMockKey(String mockKey) {
        this.mockKey = mockKey;
    }

    public MockActionVO getMatchedMockAction() {
        return matchedMockAction;
    }

    public void setMatchedMockAction(MockActionVO matchedMockAction) {
        this.matchedMockAction = matchedMockAction;
    }

    public void setExpectKey(String expectKey) {
        this.expectKey = expectKey;
    }

    public String getExpectKey(){
        return this.expectKey;
    }

    public void setXcxPayParams(HashMap params){
        this.xcxPayParams = params;
    }
    public HashMap<String, Object> getXcxPayParams(){
        return this.xcxPayParams;
    }
    public void setResponseObject(Object response){
        this.responseObject = response;
    }
    public Object getResponseObject(){
        return this.responseObject;
    }
    public void setRequestObject(Object[] request){
        this.requestObject = request;
    }
    public Object[] getRequestObject(){
        return this.requestObject;
    }
}
