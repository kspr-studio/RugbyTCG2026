package com.roguegamestudio.rugbytcg.assets;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import com.roguegamestudio.rugbytcg.CardId;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;

public class CardArtCache {
    private final EnumMap<CardId, Bitmap> cardArt = new EnumMap<>(CardId.class);
    private final EnumMap<CardId, Bitmap> cardArtFs = new EnumMap<>(CardId.class);
    private final EnumMap<CardId, Bitmap> cardArtOpp = new EnumMap<>(CardId.class);
    private final EnumMap<CardId, Bitmap> cardArtFsOpp = new EnumMap<>(CardId.class);

    public void load(AssetManager assets) {
        cardArt.clear();
        cardArtFs.clear();
        cardArtOpp.clear();
        cardArtFsOpp.clear();

        for (CardId id : CardId.values()) {
            String base = cardAssetName(id);
            if (base == null) continue;
            Bitmap normal = loadAssetBitmap(assets, "cards/" + base + ".png");
            if (normal != null) {
                cardArt.put(id, normal);
                Bitmap opp = makeOpponentArt(normal);
                if (opp != null) cardArtOpp.put(id, opp);
            }
            Bitmap fs = loadAssetBitmap(assets, "cards/" + base + "_fs.png");
            if (fs != null) {
                cardArtFs.put(id, fs);
                Bitmap oppFs = makeOpponentArt(fs);
                if (oppFs != null) cardArtFsOpp.put(id, oppFs);
            }
        }
    }

    public Bitmap get(CardId id, boolean fullscreen, boolean opponentPlayer) {
        if (fullscreen) {
            if (opponentPlayer && cardArtFsOpp.containsKey(id)) return cardArtFsOpp.get(id);
            return cardArtFs.get(id);
        }
        if (opponentPlayer && cardArtOpp.containsKey(id)) return cardArtOpp.get(id);
        return cardArt.get(id);
    }

    private Bitmap loadAssetBitmap(AssetManager am, String path) {
        try (InputStream is = am.open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            return null;
        }
    }

    private String cardAssetName(CardId id) {
        switch (id) {
            case ANCHOR: return "anchor";
            case BREAKER: return "breaker";
            case FLANKER: return "flanker";
            case OPPORTUNIST: return "opportunist";
            case PLAYMAKER: return "playmaker";
            case PROP: return "prop";
            case COUNTER_RUCK: return "counterruck";
            case QUICK_PASS: return "quickpass";
            case DRIVE: return "drive";
            case TIGHT_PLAY: return "tightplay";
            default: return null;
        }
    }

    private Bitmap makeOpponentArt(Bitmap src) {
        if (src == null) return null;
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return null;
        int w = out.getWidth();
        int h = out.getHeight();
        int size = w * h;
        int[] pixels = new int[size];
        out.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] hsv = new float[3];
        for (int i = 0; i < size; i++) {
            int c = pixels[i];
            int a = Color.alpha(c);
            if (a == 0) continue;
            Color.colorToHSV(c, hsv);
            float sat = hsv[1];
            if (sat < 0.15f) continue;
            float hue = hsv[0];
            if (hue >= 80f && hue <= 200f) {
                hue -= 130f;
                if (hue < 0f) hue += 360f;
                hsv[0] = hue;
                pixels[i] = Color.HSVToColor(a, hsv);
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }
}
