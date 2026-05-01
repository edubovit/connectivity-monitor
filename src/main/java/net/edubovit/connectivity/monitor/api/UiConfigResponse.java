package net.edubovit.connectivity.monitor.api;

public record UiConfigResponse(HomeConfig home) {

    public record HomeConfig(boolean show, String location) {
    }
}
