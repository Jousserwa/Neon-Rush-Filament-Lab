package com.neonrush.filamentspike;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.filament.Engine;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.LightManager;
import com.google.android.filament.Skybox;
import com.google.android.filament.Viewport;
import com.google.android.filament.View;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.utils.ModelViewer;
import com.google.android.filament.gltfio.Gltfio;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Public, non-proprietary Stage 2 integration harness.
 * Uses synthetic gameplay data only. No Neon Rush production source or services.
 */
public final class Stage2IntegrationActivity extends Activity implements Choreographer.FrameCallback {
    private static final String TAG = "NRStage2Public";
    private FrameLayout root;
    private SurfaceView surface;
    private ModelViewer viewer;
    private IndirectLight ambient;
    private boolean resumed;
    private boolean running;
    private boolean destroyed;
    private boolean initializing;
    private PilotOverlay overlay;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(0, 0, 12));
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        overlay = new PilotOverlay();
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        TextView hud = new TextView(this);
        hud.setText("NEON RUSH • PUBLIC INTEGRATION LAB\nFILAMENT ENVIRONMENT + SYNTHETIC GAMEPLAY");
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(11);
        hud.setPadding(18, 24, 18, 12);
        hud.setShadowLayer(5, 0, 0, Color.CYAN);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -2));
        setContentView(root);

        surface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> applyCamera());
        surface.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) {
                if (viewer == null && !initializing && !destroyed) surface.post(Stage2IntegrationActivity.this::initializeViewer);
            }
            @Override public void onViewDetachedFromWindow(android.view.View v) {
                stopLoop();
                // ModelViewer's own detach listener owns Engine teardown.
                destroyed = true;
                viewer = null;
                ambient = null;
            }
        });
    }

    private void initializeViewer() {
        if (initializing || viewer != null || destroyed || !surface.isAttachedToWindow()) return;
        initializing = true;
        try {
            Gltfio.init();
            viewer = new ModelViewer(surface, Engine.create(),
                    new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK), null);
            Engine engine = viewer.getEngine();
            viewer.getScene().setSkybox(new Skybox.Builder().build(engine));
            viewer.getScene().getSkybox().setColor(0f, 0f, 0.1f, 1f);
            viewer.getCamera().setExposure(16f, 1f / 125f, 100f);
            LightManager lm = engine.getLightManager();
            int sun = lm.getInstance(viewer.getLight());
            lm.setDirection(sun, -0.324f, -0.811f, -0.487f);

            View.MultiSampleAntiAliasingOptions msaa = new View.MultiSampleAntiAliasingOptions();
            msaa.enabled = false; msaa.sampleCount = 1; msaa.customResolve = false;
            viewer.getView().setMultiSampleAntiAliasingOptions(msaa);
            View.AmbientOcclusionOptions ao = new View.AmbientOcclusionOptions();
            ao.enabled = false; viewer.getView().setAmbientOcclusionOptions(ao);
            View.BloomOptions bloom = new View.BloomOptions();
            bloom.enabled = true; bloom.strength = 0.40f; bloom.resolution = 192;
            bloom.levels = 4; bloom.threshold = false; bloom.quality = View.QualityLevel.LOW;
            bloom.lensFlare = false; viewer.getView().setBloomOptions(bloom);
            viewer.getView().setShadowingEnabled(false);
            View.DynamicResolutionOptions dynamic = new View.DynamicResolutionOptions();
            dynamic.enabled = true; dynamic.minScale = 0.75f; dynamic.maxScale = 1f;
            dynamic.homogeneousScaling = true; dynamic.quality = View.QualityLevel.LOW;
            viewer.getView().setDynamicResolutionOptions(dynamic);

            float sh0 = 0.282095f;
            ambient = new IndirectLight.Builder()
                    .irradiance(1, new float[]{sh0, sh0, sh0})
                    .intensity(20000f).build(engine);
            viewer.getScene().setIndirectLight(ambient);

            byte[] encoded = readAsset("corridor.glb.b64");
            byte[] glb = Base64.decode(new String(encoded, java.nio.charset.StandardCharsets.US_ASCII), Base64.DEFAULT);
            viewer.loadModelGlb(ByteBuffer.wrap(glb));
            applyCamera();
        } catch (Throwable e) {
            Log.e(TAG, "Renderer initialization failed", e);
            stopLoop();
        } finally {
            initializing = false;
        }
        if (resumed) startLoop();
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private void applyCamera() {
        if (viewer == null || destroyed) return;
        int w = surface.getWidth(), h = surface.getHeight();
        if (w <= 0 || h <= 0) return;
        viewer.getCamera().lookAt(0, 1.6, 2, 0, 1.6, -9, 0, 1, 0);
        viewer.setCameraNear(0.1f); viewer.setCameraFar(50f);
        viewer.getCamera().setProjection(60.0, (double) w / h, 0.1, 50.0,
                com.google.android.filament.Camera.Fov.VERTICAL);
    }

    private void startLoop() {
        if (!resumed || running || viewer == null || destroyed) return;
        running = true; Choreographer.getInstance().postFrameCallback(this);
    }
    private void stopLoop() {
        running = false; Choreographer.getInstance().removeFrameCallback(this);
    }
    @Override public void doFrame(long frameTimeNanos) {
        ModelViewer current = viewer;
        if (!running || !resumed || current == null || destroyed) return;
        try {
            current.render(frameTimeNanos);
            if (running && resumed && viewer == current && !destroyed)
                Choreographer.getInstance().postFrameCallback(this);
        } catch (Throwable e) { Log.e(TAG, "Render loop failed", e); stopLoop(); }
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        surface.post(() -> {
            if (!resumed || isFinishing() || isDestroyed()) return;
            if (viewer == null && !initializing && !destroyed) initializeViewer();
            applyCamera(); startLoop();
        });
    }
    @Override protected void onPause() {
        resumed = false; stopLoop(); super.onPause();
    }
    @Override protected void onDestroy() {
        resumed = false; stopLoop();
        // Never call viewer.destroy(): ModelViewer destroys itself on SurfaceView detach.
        super.onDestroy();
    }

    private final class PilotOverlay extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pilotX = 0.5f;
        private float pilotY = 0.70f;
        private float downX;
        PilotOverlay() { super(Stage2IntegrationActivity.this); setBackgroundColor(Color.TRANSPARENT); setLayerType(View.LAYER_TYPE_HARDWARE, null); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            p.setColor(Color.argb(180, 0, 229, 255)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
            c.drawRoundRect(w*0.08f,h*0.32f,w*0.92f,h*0.82f,22,22,p);
            p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(180,255,170,0));
            c.drawRoundRect(w*0.18f,h*0.50f,w*0.29f,h*0.57f,8,8,p);
            c.drawRoundRect(w*0.70f,h*0.39f,w*0.82f,h*0.46f,8,8,p);
            float x=pilotX*w, y=pilotY*h;
            p.setColor(Color.CYAN); c.drawCircle(x,y,Math.min(w,h)*0.035f,p);
            p.setColor(Color.WHITE); c.drawCircle(x,y,Math.min(w,h)*0.014f,p);
            p.setColor(Color.argb(180,0,229,255)); p.setTextSize(24);
            c.drawText("DRAG TO STEER  •  SYNTHETIC PILOT", 18, h-28, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getX();return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE){pilotX=Math.max(0.12f,Math.min(0.88f,e.getX()/Math.max(1f,getWidth())));invalidate();return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){performClick();return true;}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
