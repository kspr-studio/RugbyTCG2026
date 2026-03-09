package com.roguegamestudio.rugbytcg.audio;

import com.roguegamestudio.rugbytcg.CardId;
import com.roguegamestudio.rugbytcg.engine.AnnouncerEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class AnnouncerPlaylistBuilder {
    private static final String ROOT = "snd/announcer/";
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;
    private static final int SCORE_STEP = 5;
    private static final Map<CardId, String> CARD_CLIP_MAP = createCardClipMap();

    private AnnouncerPlaylistBuilder() {
    }

    public static List<String> buildPlaylist(AnnouncerEvent event) {
        if (event == null || event.type == null) return Collections.emptyList();

        List<String> clips = new ArrayList<>();
        switch (event.type) {
            case MATCH_START:
                clips.add(path("kickoff.wav"));
                break;
            case TURN_START:
                if (event.side == AnnouncerEvent.Side.HOME) {
                    clips.add(path("turn_home.wav"));
                } else if (event.side == AnnouncerEvent.Side.AWAY) {
                    clips.add(path("turn_away.wav"));
                } else {
                    return Collections.emptyList();
                }
                break;
            case CARD_PLAYED:
                if (!addCardClip(event.cardId, clips)) {
                    return Collections.emptyList();
                }
                break;
            case PHASE_RESULT:
                if (event.side == AnnouncerEvent.Side.HOME) {
                    clips.add(path("phase_home_win.wav"));
                } else if (event.side == AnnouncerEvent.Side.AWAY) {
                    clips.add(path("phase_away_win.wav"));
                } else if (event.side == AnnouncerEvent.Side.NONE) {
                    clips.add(path("phase_tie.wav"));
                } else {
                    return Collections.emptyList();
                }
                break;
            case TRY_SCORED:
                if (event.side == AnnouncerEvent.Side.HOME) {
                    clips.add(path("home_scores_try.wav"));
                } else if (event.side == AnnouncerEvent.Side.AWAY) {
                    clips.add(path("away_scores_try.wav"));
                } else {
                    return Collections.emptyList();
                }
                break;
            case MATCH_END:
                clips.add(path("kickoff.wav"));
                clips.add(path("full_time.wav"));
                if (event.side == AnnouncerEvent.Side.HOME) {
                    clips.add(path("home_wins.wav"));
                } else if (event.side == AnnouncerEvent.Side.AWAY) {
                    clips.add(path("away_wins.wav"));
                } else if (event.side == AnnouncerEvent.Side.NONE) {
                    clips.add(path("match_tied.wav"));
                } else {
                    return Collections.emptyList();
                }
                addScoreBlock(event.homeScore, event.awayScore, clips);
                break;
            default:
                return Collections.emptyList();
        }
        return Collections.unmodifiableList(clips);
    }

    private static boolean addCardClip(CardId cardId, List<String> out) {
        if (cardId == null || out == null) return false;
        String clip = CARD_CLIP_MAP.get(cardId);
        if (clip == null || clip.isEmpty()) return false;
        out.add(path(clip));
        return true;
    }

    private static void addScoreBlock(int homeScore, int awayScore, List<String> out) {
        if (out == null) return;
        if (!isSupportedScore(homeScore) || !isSupportedScore(awayScore)) return;
        out.add(path("score.wav"));
        out.add(path("home.wav"));
        out.add(path("num_" + homeScore + ".wav"));
        out.add(path("away.wav"));
        out.add(path("num_" + awayScore + ".wav"));
    }

    private static boolean isSupportedScore(int score) {
        return score >= MIN_SCORE && score <= MAX_SCORE && score % SCORE_STEP == 0;
    }

    private static String path(String fileName) {
        return ROOT + fileName;
    }

    private static Map<CardId, String> createCardClipMap() {
        EnumMap<CardId, String> map = new EnumMap<>(CardId.class);
        map.put(CardId.FLANKER, "card_flanker.wav");
        map.put(CardId.PROP, "card_prop.wav");
        map.put(CardId.PLAYMAKER, "card_playmaker.wav");
        map.put(CardId.BREAKER, "card_breaker.wav");
        map.put(CardId.ANCHOR, "card_anchor.wav");
        map.put(CardId.OPPORTUNIST, "card_opportunist.wav");
        map.put(CardId.COUNTER_RUCK, "card_counter_ruck.wav");
        map.put(CardId.QUICK_PASS, "card_quick_pass.wav");
        map.put(CardId.DRIVE, "card_drive.wav");
        map.put(CardId.TIGHT_PLAY, "card_tight_play.wav");
        return map;
    }
}
