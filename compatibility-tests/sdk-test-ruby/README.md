# Ruby SDK contracts

Ruby 3.4 and the official Compute, Storage and Monitoring clients are locked in
`Gemfile.lock`. Tests use synthetic credentials and random fixture projects.
They assert API contracts, not VM execution, guest health or HTTP forwarding.

Build the container with `docker build -t floci-sdk-ruby .`. Run it on the same
Docker network as the emulator, setting `FLOCI_GCP_ENDPOINT` to its HTTP origin
and `FLOCI_GCP_GRPC_ENDPOINT` to its gRPC host and port. Mount a writable directory
at `/results` for JUnit output. Monitoring uses gRPC; Compute uses REST.

The normal entrypoint runs `test/all.rb`. To verify persistence, run
`bundle exec ruby test/restart_probe.rb` with `FLOCI_GCP_RESTART_PHASE=seed`,
restart the emulator while retaining its persistent data volume, then run with
`FLOCI_GCP_RESTART_PHASE=verify`. Keep `FLOCI_GCP_RESTART_STATE` on a shared
writable volume across these two invocations. The Java `RestartContractTest`
provides the same two phases through the same environment variables, with a
separate state file. Without a phase override the Java test performs a normal
round trip; only the two-phase runner demonstrates restart recovery.

The Cloud SDK contracts workflow builds from the triggering commit, runs the JVM
regression suite, builds the native executable, then runs both SDKs and the
restart phases. Its evidence includes source SHA, image ID, native binary hash
and JUnit results. The existing compatibility workflow also includes Ruby.

Signed transfers test URL shape, bytes and expiration. Floci does not verify
cryptographic signatures. Downscoped prefix tests cover supported CAB conditions
and roles, not the complete Google IAM authorization model. Synthetic Monitoring
system metric descriptors and points are removed with the disposable emulator
volume, because the native API does not allow deleting system descriptors.
