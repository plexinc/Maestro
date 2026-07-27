package maestro.orchestra.yaml

data class YamlReadFile(
    val file: String,
    val outputVariable: String,
    val `when`: YamlCondition? = null,
    val label: String? = null,
    val optional: Boolean = false,
)
