package com.roguegamestudio.rugbytcg.ui.render;

import android.content.res.Resources;
import android.graphics.Paint;

import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.assets.CardArtCache;
import com.roguegamestudio.rugbytcg.assets.TextureCache;

import java.util.IdentityHashMap;

public class RenderContext {
    public final Resources resources;
    public final CardArtCache cardArtCache;
    public final TextureCache textureCache;

    public final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    public final Paint staBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final Paint staFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public final IdentityHashMap<PlayerCard, Float> staVis = new IdentityHashMap<>();

    public long lastFrameMs = 0L;
    public float frameDtSeconds = 0f;
    public long frameElapsedMs = 0L;

    public RenderContext(Resources resources, CardArtCache cardArtCache, TextureCache textureCache) {
        this.resources = resources;
        this.cardArtCache = cardArtCache;
        this.textureCache = textureCache;
    }

    public float dp(float v) {
        return v * resources.getDisplayMetrics().density;
    }
}
