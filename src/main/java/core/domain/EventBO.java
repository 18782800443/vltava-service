package core.domain;

import com.testhuamou.vltava.domain.mock.MockActionVO;

import java.util.UUID;

/**
 * @author Rob
 */
public class EventBO {
    private Boolean match;
    private String mockKey;
    private MockActionVO matchedMockAction;
    private String reference;

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
}
