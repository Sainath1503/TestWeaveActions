package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ApiDbAutoMappingSkill {
    public JSONArray parse(JSONObject response) {
        JSONArray values = response.optJSONArray("apiDbMappings");
        return values == null ? new JSONArray() : values;
    }
}
