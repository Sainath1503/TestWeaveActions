package agenticai;

import org.json.JSONObject;

public final class DbAgentPromptBuilder {
    public String build(JSONObject context) {
        return """
                You are the VeyraAI DB Analysis Agent. Return strict JSON only.
                Generate dbValidations and apiDbMappings using only the supplied context.
                Include id, table, column/rule/expected, confidence, reason, contextSources, and status.
                Mapping entries must include API JSONPath/name/dataType, DB schema/table/column/dataType,
                confidence, reason, transformation, normalization, nullHandling, comparison, and status.
                Supported DB rules include notNull, nullAllowed, dataType, length, minimum, maximum,
                numericRange, enum, unique, primaryKey, foreignKey, dateFormat, timestampTolerance,
                regex, rowCount, duplicateDetection, aggregation, crossColumnConsistency, defaultValue,
                and conditional.

                CONTEXT:
                %s
                """.formatted(context.toString(2));
    }
}
