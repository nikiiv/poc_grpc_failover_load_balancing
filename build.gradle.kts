tasks.register("start_broker") {
    group = "demo"
    description = "Runs the lifecycle broker (:lifecycle-broker:run; gRPC :7100)."
    dependsOn(":lifecycle-broker:run")
}

tasks.register<JavaExec>("start_bff_t1") {
    group = "demo"
    description = "Runs trading BFF #1 on HTTP :8080, NODE_ID=bff-t-1."
    dependsOn(":bff:classes")
    classpath = project(":bff").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("com.example.poc.bff.BffApplication")
    environment("ROLE", "trading")
    environment("NODE_ID", "bff-t-1")
    environment("BROKER_TARGET", "127.0.0.1:7100")
    environment("ADVERTISED_ADDRESS", "127.0.0.1:8080")
    systemProperty("micronaut.server.port", "8080")
}

tasks.register("start_ui") {
    group = "demo"
    description = "Runs the Vite dev server (:web-client:start_bff_client)."
    dependsOn(":web-client:start_bff_client")
}

fun TaskContainer.registerServer(taskName: String, id: String, port: Int) {
    register<JavaExec>(taskName) {
        group = "demo"
        description = "Runs backend SERVER_ID=$id (role=trading) on :$port."
        dependsOn(":grpc-server:classes")
        classpath = project(":grpc-server").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
        mainClass.set("com.example.poc.server.ServerApp")
        environment("SERVER_ID", id)
        environment("SERVER_PORT", port.toString())
        environment("ROLE", "trading")
        environment("ADVERTISED_HOST", "127.0.0.1")
        environment("BROKER_TARGET", "127.0.0.1:7100")
    }
}

tasks.registerServer("start_server_t1", "server-t-1", 9101)
tasks.registerServer("start_server_t2", "server-t-2", 9102)
tasks.registerServer("start_server_t3", "server-t-3", 9103)
