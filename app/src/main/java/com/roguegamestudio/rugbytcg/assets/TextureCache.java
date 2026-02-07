package com.roguegamestudio.rugbytcg.assets;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;

public class TextureCache {
    private Bitmap fieldTexture;

    public void load(AssetManager assets) {
        fieldTexture = loadAssetBitmap(assets, "field.png");
    }

    public Bitmap getFieldTexture() {
        return fieldTexture;
    }

    private Bitmap loadAssetBitmap(AssetManager am, String path) {
        try (InputStream is = am.open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            return null;
        }
    }
}
