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
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.IndexBuffer;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.LightManager;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.TransformManager;
import com.google.android.filament.VertexBuffer;
import com.google.android.filament.View;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.utils.ModelViewer;
import com.google.android.filament.gltfio.Gltfio;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Public, non-proprietary Stage 2 integration harness.
 * Uses synthetic gameplay data only. No Neon Rush production source or services.
 */
public final class Stage2IntegrationActivity extends Activity implements Choreographer.FrameCallback {
    private static final String TAG = "NRStage2Public";
    private static final float OBSTACLE_START_Z = -8.5f;
    private static final float OBSTACLE_SPEED = 0.035f;

    private FrameLayout root;
    private SurfaceView surface;
    private ModelViewer viewer;
    private IndirectLight ambient;
    private boolean resumed;
    private boolean running;
    private boolean destroyed;
    private boolean initializing;
    private PilotOverlay overlay;

    // One reusable Filament renderable: geometry is built once; only its transform moves.
    private int obstacleEntity;
    private VertexBuffer obstacleVertices;
    private IndexBuffer obstacleIndices;
    private float obstacleZ = OBSTACLE_START_Z;
    private long statsWindowStartNs;
    private long statsCallbacks;
    private long statsAccepted;
    private long statsRejected;
    private long statsRenderCpuNs;
    private long statsMaxRenderCpuNs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(0, 0, 12));
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        overlay = new PilotOverlay();
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        TextView hud = new TextView(this);
        hud.setText("NEON RUSH • PUBLIC INTEGRATION LAB\nFILAMENT ENVIRONMENT + 3D TEST OBSTACLE");
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
            viewer.getScene().setSkybox(new com.google.android.filament.Skybox.Builder().build(engine));
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
            create3DObstacle(engine);
            applyCamera();
        } catch (Throwable e) {
            Log.e(TAG, "Renderer initialization failed", e);
            stopLoop();
        } finally {
            initializing = false;
        }
        if (resumed) startLoop();
    }

    private void create3DObstacle(Engine engine) {
        // Flat-shaded rectangular obstacle: 1.0 wide x 0.7 high x 0.5 deep.
        // Each face owns four vertices so its normal remains perpendicular to that face.
        final float hx = 0.5f, hy = 0.35f, hz = 0.25f;
        float[] positions = {
                // Front (+Z)
                -hx,-hy,hz,  hx,-hy,hz,  hx,hy,hz,  -hx,hy,hz,
                // Back (-Z)
                hx,-hy,-hz,  -hx,-hy,-hz,  -hx,hy,-hz,  hx,hy,-hz,
                // Right (+X)
                hx,-hy,hz,  hx,-hy,-hz,  hx,hy,-hz,  hx,hy,hz,
                // Left (-X)
                -hx,-hy,-hz,  -hx,-hy,hz,  -hx,hy,hz,  -hx,hy,-hz,
                // Top (+Y)
                -hx,hy,hz,  hx,hy,hz,  hx,hy,-hz,  -hx,hy,-hz,
                // Bottom (-Y)
                -hx,-hy,-hz,  hx,-hy,-hz,  hx,-hy,hz,  -hx,-hy,hz
        };
        float[] normals = {
                0,0,1,  0,0,1,  0,0,1,  0,0,1,
                0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1,
                1,0,0,  1,0,0,  1,0,0,  1,0,0,
                -1,0,0, -1,0,0, -1,0,0, -1,0,0,
                0,1,0,  0,1,0,  0,1,0,  0,1,0,
                0,-1,0, 0,-1,0, 0,-1,0, 0,-1,0
        };
        short[] indices = {
                0,1,2, 0,2,3,
                4,5,6, 4,6,7,
                8,9,10, 8,10,11,
                12,13,14, 12,14,15,
                16,17,18, 16,18,19,
                20,21,22, 20,22,23
        };

        ByteBuffer vertexData = ByteBuffer.allocateDirect(positions.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float value : positions) vertexData.putFloat(value);
        vertexData.flip();

        ByteBuffer normalData = ByteBuffer.allocateDirect(normals.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float value : normals) normalData.putFloat(value);
        normalData.flip();

        ByteBuffer indexData = ByteBuffer.allocateDirect(indices.length * 2)
                .order(ByteOrder.nativeOrder());
        for (short value : indices) indexData.putShort(value);
        indexData.flip();

        obstacleVertices = new VertexBuffer.Builder()
                .vertexCount(24)
                .bufferCount(2)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT3)
                .attribute(VertexBuffer.VertexAttribute.NORMAL, 1,
                        VertexBuffer.AttributeType.FLOAT3)
                .build(engine);
        obstacleVertices.setBufferAt(engine, 0, vertexData);
        obstacleVertices.setBufferAt(engine, 1, normalData);

        obstacleIndices = new IndexBuffer.Builder()
                .indexCount(indices.length)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine);
        obstacleIndices.setBuffer(engine, indexData);

        obstacleEntity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .geometryType(RenderableManager.Builder.GeometryType.DYNAMIC)
                .boundingBox(new Box(0f, 0f, 0f, hx, hy, hz))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                        obstacleVertices, obstacleIndices, 0, indices.length)
                // A deliberate fallback material remains until a compiled PBR material is added.
                .build(engine, obstacleEntity);

        TransformManager transforms = engine.getTransformManager();
        transforms.create(obstacleEntity);
        // Center at half-height so the obstacle's bottom sits on the y=0 floor plane.
        setObstacleTransform(transforms, 1.0f, hy, obstacleZ);
        viewer.getScene().addEntity(obstacleEntity);
    }

    private void setObstacleTransform(TransformManager transforms, float x, float y, float z) {
        int instance = transforms.getInstance(obstacleEntity);
        float[] matrix = {
                1,0,0,0,
                0,1,0,0,
                0,0,1,0,
                x,y,z,1
        };
        transforms.setTransform(instance, matrix);
    }

    private void update3DObstacle(long frameTimeNanos) {
        if (viewer == null || obstacleEntity == 0) return;
        obstacleZ += OBSTACLE_SPEED;
        if (obstacleZ > -1.5f) obstacleZ = OBSTACLE_START_Z;
        setObstacleTransform(viewer.getEngine().getTransformManager(), 1.0f, 0.35f, obstacleZ);
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
        running = true;
        statsWindowStartNs = System.nanoTime();
        statsCallbacks = statsAccepted = statsRejected = statsRenderCpuNs = statsMaxRenderCpuNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }
    private void stopLoop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }
    @Override public void doFrame(long frameTimeNanos) {
        ModelViewer current = viewer;
        if (!running || !resumed || current == null || destroyed) return;
        long renderStartNs = System.nanoTime();
        try {
            statsCallbacks++;
            update3DObstacle(frameTimeNanos);
            boolean accepted = current.render(frameTimeNanos);
            long renderCpuNs = System.nanoTime() - renderStartNs;
            statsRenderCpuNs += renderCpuNs;
            if (renderCpuNs > statsMaxRenderCpuNs) statsMaxRenderCpuNs = renderCpuNs;
            if (accepted) statsAccepted++; else statsRejected++;

            long now = System.nanoTime();
            if (now - statsWindowStartNs >= 1_000_000_000L) {
                double seconds = (now - statsWindowStartNs) / 1_000_000_000.0;
                double avgMs = statsCallbacks == 0 ? 0 : statsRenderCpuNs / 1_000_000.0 / statsCallbacks;
                Log.i(TAG, String.format(java.util.Locale.US,
                        "PERF callbacks/s=%.1f acceptedRender/s=%.1f rejected=%d avgRenderCPUms=%.2f maxRenderCPUms=%.2f",
                        statsCallbacks / seconds, statsAccepted / seconds, statsRejected, avgMs,
                        statsMaxRenderCpuNs / 1_000_000.0));
                statsWindowStartNs = now;
                statsCallbacks = statsAccepted = statsRejected = statsRenderCpuNs = statsMaxRenderCpuNs = 0;
            }
            if (running && resumed && viewer == current && !destroyed)
                Choreographer.getInstance().postFrameCallback(this);
        } catch (Throwable e) {
            Log.e(TAG, "Render loop failed", e);
            stopLoop();
        }
    }

    private void releaseObstacleResources() {
        ModelViewer current = viewer;
        if (current == null || obstacleEntity == 0) return;
        try {
            Engine engine = current.getEngine();
            current.getScene().remove(obstacleEntity);
            engine.getRenderableManager().destroy(obstacleEntity);
            engine.getTransformManager().destroy(obstacleEntity);
            EntityManager.get().destroy(obstacleEntity);
            if (obstacleVertices != null) engine.destroyVertexBuffer(obstacleVertices);
            if (obstacleIndices != null) engine.destroyIndexBuffer(obstacleIndices);
        } catch (Throwable e) {
            Log.w(TAG, "Obstacle cleanup skipped because renderer teardown may already have started", e);
        } finally {
            obstacleEntity = 0;
            obstacleVertices = null;
            obstacleIndices = null;
        }
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
        // Release only our obstacle resources while ModelViewer's Engine is still valid.
        releaseObstacleResources();
        // Never call viewer.destroy(): ModelViewer owns Engine teardown on SurfaceView detach.
        super.onDestroy();
    }

    private final class PilotOverlay extends android.view.View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pilotX = 0.5f;
        private float pilotY = 0.70f;
        PilotOverlay() { super(Stage2IntegrationActivity.this); setBackgroundColor(Color.TRANSPARENT); setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            float x=pilotX*w, y=pilotY*h;
            p.setColor(Color.CYAN); c.drawCircle(x,y,Math.min(w,h)*0.035f,p);
            p.setColor(Color.WHITE); c.drawCircle(x,y,Math.min(w,h)*0.014f,p);
            p.setColor(Color.argb(180,0,229,255)); p.setTextSize(24);
            c.drawText("DRAG TO STEER  •  3D OBSTACLE TEST", 18, h-28, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_DOWN)return true;
            if(e.getAction()==MotionEvent.ACTION_MOVE){pilotX=Math.max(0.12f,Math.min(0.88f,e.getX()/Math.max(1f,getWidth())));invalidate();return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){performClick();return true;}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
