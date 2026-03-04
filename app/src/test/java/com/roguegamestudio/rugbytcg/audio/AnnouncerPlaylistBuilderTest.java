package com.roguegamestudio.rugbytcg.audio;

import com.roguegamestudio.rugbytcg.CardId;
import com.roguegamestudio.rugbytcg.engine.AnnouncerEvent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AnnouncerPlaylistBuilderTest {
    @Test
    public void buildPlaylist_cardPlayedHome_usesHomePlaysAndCardClip() {
        AnnouncerEvent event = event(
                AnnouncerEvent.Type.CARD_PLAYED,
                AnnouncerEvent.Side.HOME,
                CardId.QUICK_PASS,
                0,
                0
        );

        List<String> playlist = AnnouncerPlaylistBuilder.buildPlaylist(event);

        assertEquals(Arrays.asList(
                "snd/announcer/home_plays.wav",
                "snd/announcer/card_quick_pass.wav"
        ), playlist);
    }

    @Test
    public void buildPlaylist_tryScored_usesTryClipAndScoreBlock() {
        AnnouncerEvent event = event(
                AnnouncerEvent.Type.TRY_SCORED,
                AnnouncerEvent.Side.HOME,
                null,
                15,
                10
        );

        List<String> playlist = AnnouncerPlaylistBuilder.buildPlaylist(event);

        assertEquals(Arrays.asList(
                "snd/announcer/home_scores_try.wav",
                "snd/announcer/score.wav",
                "snd/announcer/home.wav",
                "snd/announcer/num_15.wav",
                "snd/announcer/away.wav",
                "snd/announcer/num_10.wav"
        ), playlist);
    }

    @Test
    public void buildPlaylist_matchEndTie_usesFullTimeTieAndScoreBlock() {
        AnnouncerEvent event = event(
                AnnouncerEvent.Type.MATCH_END,
                AnnouncerEvent.Side.NONE,
                null,
                20,
                20
        );

        List<String> playlist = AnnouncerPlaylistBuilder.buildPlaylist(event);

        assertEquals(Arrays.asList(
                "snd/announcer/full_time.wav",
                "snd/announcer/match_tied.wav",
                "snd/announcer/score.wav",
                "snd/announcer/home.wav",
                "snd/announcer/num_20.wav",
                "snd/announcer/away.wav",
                "snd/announcer/num_20.wav"
        ), playlist);
    }

    @Test
    public void buildPlaylist_unknownScore_omitsScoreBlock() {
        AnnouncerEvent event = event(
                AnnouncerEvent.Type.TRY_SCORED,
                AnnouncerEvent.Side.AWAY,
                null,
                17,
                10
        );

        List<String> playlist = AnnouncerPlaylistBuilder.buildPlaylist(event);

        assertEquals(Collections.singletonList("snd/announcer/away_scores_try.wav"), playlist);
    }

    @Test
    public void buildPlaylist_cardIdMapping_usesChancerClipForOpportunist() {
        AnnouncerEvent event = event(
                AnnouncerEvent.Type.CARD_PLAYED,
                AnnouncerEvent.Side.AWAY,
                CardId.OPPORTUNIST,
                0,
                0
        );

        List<String> playlist = AnnouncerPlaylistBuilder.buildPlaylist(event);

        assertEquals(Arrays.asList(
                "snd/announcer/away_plays.wav",
                "snd/announcer/card_opportunist.wav"
        ), playlist);
    }

    private static AnnouncerEvent event(AnnouncerEvent.Type type,
                                        AnnouncerEvent.Side side,
                                        CardId cardId,
                                        int homeScore,
                                        int awayScore) {
        return new AnnouncerEvent(
                type,
                side,
                cardId,
                "",
                homeScore,
                awayScore,
                0,
                0L,
                false,
                0L
        );
    }
}
