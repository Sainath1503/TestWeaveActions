package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DbValidationSuggestionSkill {
    public JSONArray parse(JSONObject response) {
        JSONArray values = response.optJSONArray("dbValidations");
        return values == null ? new JSONArray() : values;
    }
}
