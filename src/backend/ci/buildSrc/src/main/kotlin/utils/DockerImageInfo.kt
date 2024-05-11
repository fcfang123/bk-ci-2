package utils

enum class DockerImageInfo(
    val dockerImageName: String,
    val hostPort: Int,
    val containerPort: Int,
    val env: Map<String, String>? = emptyMap()
) {
    REDIS("redis:5.0.3-alpine", 30002, 6379),
    ELASTICSEARCH("docker.elastic.co/elasticsearch/elasticsearch:7.14.0", 30014, 9200,
                  mapOf(
                      "discovery.type" to "single-node",
                      "xpack.security.enabled" to "false",
                      "ELASTIC_PASSWORD" to "blueking"
                  )
    ),
    INFLUXDB("docker.io/bitnami/influxdb:1.8.3-debian-10-r88", 30006, 8086,
             mapOf("INFLUXDB_ADMIN_USER_PASSWORD" to "blueking")
    ),
    RABBITMQ("heidiks/rabbitmq-delayed-message-exchange:3.13.0-management", 30003, 5672,
             mapOf(
                 "RABBITMQ_DEFAULT_USER" to "admin",
                 "RABBITMQ_DEFAULT_PASS" to "blueking"
             )
    ),
    MYSQL("mysql:5.7.26", 30001, 3306,
          mapOf(
              "MYSQL_ROOT_PASSWORD" to "blueking"
          )
    );

    companion object {
        fun getHostPortList(): List<Int> {
            return values().map { it.hostPort }
        }
    }
}
