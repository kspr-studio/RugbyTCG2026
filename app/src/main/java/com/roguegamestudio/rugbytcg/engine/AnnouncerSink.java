package com.roguegamestudio.rugbytcg.engine;

public interface AnnouncerSink {
    AnnouncerSink NO_OP = event -> {
    };

    void onAnnouncerEvent(AnnouncerEvent event);
}
