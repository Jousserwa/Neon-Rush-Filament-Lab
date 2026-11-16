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
    private static final float OBSTACLE_SPEED_UNITS_PER_SECOND = 2.0f;
    private static final float PILOT_Z = -2.5f;
    private static final float PILOT_HALF_WIDTH = 0.40f;
    private static final float PILOT_HALF_DEPTH = 0.18f;
    private static final float BASE_HALF_X = 0.325f, BASE_HALF_Y = 0.225f, BASE_HALF_Z = 0.125f;

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
    private int pilotRootEntity;
    private int pilotBodyEntity, pilotHeadEntity, pilotPackEntity;
    private int pilotLeftArmEntity, pilotRightArmEntity, pilotLeftLegEntity, pilotRightLegEntity;
    private float pilotWorldX;
    private boolean obstacleCollisionReported;
    private VertexBuffer obstacleVertices;
    private IndexBuffer obstacleIndices;
    private float obstacleZ = OBSTACLE_START_Z;
    private long previousFrameTimeNs;
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
        TextView footer = new TextView(this);
        footer.setText("DRAG TO STEER • 3D PILOT + OBSTACLE");
        footer.setTextColor(Color.rgb(205, 225, 232));
        footer.setTextSize(11);
        footer.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        footer.setBackgroundColor(Color.argb(190, 5, 13, 22));
        footer.setGravity(android.view.Gravity.CENTER);
        footer.setPadding(12, 8, 12, 18);
        footer.setClickable(false);
        footer.setFocusable(false);
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(-1, -2,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        root.addView(footer, footerParams);
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
            msaa.enabled = true; msaa.sampleCount = 2; msaa.customResolve = false;
            viewer.getView().setMultiSampleAntiAliasingOptions(msaa);
            View.AmbientOcclusionOptions ao = new View.AmbientOcclusionOptions();
            ao.enabled = false; viewer.getView().setAmbientOcclusionOptions(ao);
            View.BloomOptions bloom = new View.BloomOptions();
            bloom.enabled = true; bloom.strength = 0.24f; bloom.resolution = 192;
            bloom.levels = 4; bloom.threshold = true; bloom.quality = View.QualityLevel.LOW;
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
            create3DPilot(engine);
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
        final float hx = 0.325f, hy = 0.225f, hz = 0.125f;
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
        // Filament encodes the tangent frame (including its normal) as a quaternion.
        // One frame per flat-shaded face: front, back, right, left, top, bottom.
        final float q = 0.70710678f;
        float[] tangents = {
                0,0,0,1,  0,0,0,1,  0,0,0,1,  0,0,0,1,
                0,1,0,0,  0,1,0,0,  0,1,0,0,  0,1,0,0,
                0,q,0,q,  0,q,0,q,  0,q,0,q,  0,q,0,q,
                0,-q,0,q, 0,-q,0,q, 0,-q,0,q, 0,-q,0,q,
                -q,0,0,q, -q,0,0,q, -q,0,0,q, -q,0,0,q,
                q,0,0,q,   q,0,0,q,   q,0,0,q,   q,0,0,q
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

        ByteBuffer tangentData = ByteBuffer.allocateDirect(tangents.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float value : tangents) tangentData.putFloat(value);
        tangentData.flip();

        ByteBuffer indexData = ByteBuffer.allocateDirect(indices.length * 2)
                .order(ByteOrder.nativeOrder());
        for (short value : indices) indexData.putShort(value);
        indexData.flip();

        obstacleVertices = new VertexBuffer.Builder()
                .vertexCount(24)
                .bufferCount(2)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT3)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1,
                        VertexBuffer.AttributeType.FLOAT4)
                .build(engine);
        obstacleVertices.setBufferAt(engine, 0, vertexData);
        obstacleVertices.setBufferAt(engine, 1, tangentData);

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

    private void create3DPilot(Engine engine) {
        // Primitive low-poly pilot blockout made from actual Filament renderables.
        pilotRootEntity = EntityManager.get().create();
        engine.getTransformManager().create(pilotRootEntity);
        pilotBodyEntity = createPilotPart(engine);
        pilotHeadEntity = createPilotPart(engine);
        pilotPackEntity = createPilotPart(engine);
        pilotLeftArmEntity = createPilotPart(engine);
        pilotRightArmEntity = createPilotPart(engine);
        pilotLeftLegEntity = createPilotPart(engine);
        pilotRightLegEntity = createPilotPart(engine);
        updatePilotTransform(engine.getTransformManager(), 0.5f);
    }

    private int createPilotPart(Engine engine) {
        int entity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .geometryType(RenderableManager.Builder.GeometryType.DYNAMIC)
                .boundingBox(new Box(0f, 0f, 0f, 0.5f, 0.35f, 0.25f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                        obstacleVertices, obstacleIndices, 0, 36)
                .build(engine, entity);
        TransformManager transforms = engine.getTransformManager();
        int childInstance = transforms.getInstance(entity);
        int rootInstance = transforms.getInstance(pilotRootEntity);
        transforms.setParent(childInstance, rootInstance);
        viewer.getScene().addEntity(entity);
        return entity;
    }

    private void setPilotPart(TransformManager transforms, int entity,
                              float x, float y, float z, float halfX, float halfY, float halfZ) {
        int instance = transforms.getInstance(entity);
        // Convert desired WORLD half-extents into scale factors for the shared base box.
        float sx = halfX / BASE_HALF_X;
        float sy = halfY / BASE_HALF_Y;
        float sz = halfZ / BASE_HALF_Z;
        float[] matrix = {
                sx,0,0,0,
                0,sy,0,0,
                0,0,sz,0,
                x,y,z,1
        };
        transforms.setTransform(instance, matrix);
    }

    private void updatePilotTransform(TransformManager transforms, float normalizedX) {
        float depth = 2.0f - PILOT_Z;
        float aspect = surface.getHeight() > 0
                ? (float) surface.getWidth() / (float) surface.getHeight() : 0.5f;
        float visibleHalfWidth = depth * 0.57735027f * aspect;
        float requestedX = (normalizedX * 2f - 1f) * visibleHalfWidth;
        // Keep the widest pilot component (the backpack) inside the camera frustum.
        float safeCenterLimit = Math.max(0f, visibleHalfWidth - PILOT_HALF_WIDTH - 0.04f);
        pilotWorldX = Math.max(-safeCenterLimit, Math.min(safeCenterLimit, requestedX));

        // Root carries steering; each body part is positioned in pilot-local coordinates.
        int rootInstance = transforms.getInstance(pilotRootEntity);
        float[] rootMatrix = {1,0,0,0, 0,1,0,0, 0,0,1,0, pilotWorldX,0,0,1};
        transforms.setTransform(rootInstance, rootMatrix);
        setPilotPart(transforms, pilotBodyEntity, 0f, 0.95f, 0f,
                0.23f, 0.34f, 0.16f);
        // Head overlaps the torso slightly so the neck joint cannot separate.
        setPilotPart(transforms, pilotHeadEntity, 0f, 1.43f, 0f,
                0.18f, 0.20f, 0.18f);
        setPilotPart(transforms, pilotPackEntity, 0f, 0.92f, 0.20f,
                0.18f, 0.28f, 0.12f);
        // Arms overlap the torso shoulders; legs overlap the lower torso and reach floor y=0.
        setPilotPart(transforms, pilotLeftArmEntity, -0.29f, 0.94f, 0f,
                0.09f, 0.28f, 0.10f);
        setPilotPart(transforms, pilotRightArmEntity, 0.29f, 0.94f, 0f,
                0.09f, 0.28f, 0.10f);
        setPilotPart(transforms, pilotLeftLegEntity, -0.12f, 0.30f, 0f,
                0.105f, 0.30f, 0.12f);
        setPilotPart(transforms, pilotRightLegEntity, 0.12f, 0.30f, 0f,
                0.105f, 0.30f, 0.12f);
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
        // Move in world units per second, independent of device refresh rate.
        // Cap a resumed/stalled frame to prevent a large teleport.
        float deltaSeconds = previousFrameTimeNs == 0L ? 0f
                : Math.min(0.05f, Math.max(0f,
                    (frameTimeNanos - previousFrameTimeNs) / 1_000_000_000f));
        previousFrameTimeNs = frameTimeNanos;
        obstacleZ += OBSTACLE_SPEED_UNITS_PER_SECOND * deltaSeconds;
        if (!obstacleCollisionReported && overlapsPilotObstacleAabb()) {
            Log.i(TAG, "TEST_COLLISION_AABB: pilot and obstacle world-space bounds overlap");
            obstacleCollisionReported = true;
        }
        if (!obstacleCollisionReported && obstacleZ > PILOT_Z + 0.42f) {
            Log.i(TAG, "TEST_PASS_AABB: obstacle cleared pilot bounds");
            obstacleCollisionReported = true;
        }
        if (obstacleZ > -0.5f) {
            obstacleZ = OBSTACLE_START_Z;
            obstacleCollisionReported = false;
        }
        setObstacleTransform(viewer.getEngine().getTransformManager(), 1.0f, 0.225f, obstacleZ);
    }

    // Conservative world-space AABB test using the assembled pilot extents and obstacle bounds.
    private boolean overlapsPilotObstacleAabb() {
        final float pilotHalfX = PILOT_HALF_WIDTH, pilotMinY = 0.0f, pilotMaxY = 1.63f;
        final float pilotHalfZ = PILOT_HALF_DEPTH;
        final float obstacleCenterX = 1.0f, obstacleHalfX = 0.325f;
        final float obstacleCenterY = 0.225f, obstacleHalfY = 0.225f;
        final float obstacleHalfZ = 0.125f;
        boolean x = Math.abs(pilotWorldX - obstacleCenterX) <= pilotHalfX + obstacleHalfX;
        boolean y = pilotMaxY >= obstacleCenterY - obstacleHalfY
                && pilotMinY <= obstacleCenterY + obstacleHalfY;
        boolean z = Math.abs(PILOT_Z - obstacleZ) <= pilotHalfZ + obstacleHalfZ;
        return x && y && z;
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
        // Slight downward pitch keeps the y=0 floor and pilot feet inside the portrait frustum.
        viewer.getCamera().lookAt(0, 1.6, 2, 0, 1.0, -9, 0, 1, 0);
        viewer.setCameraNear(0.1f); viewer.setCameraFar(50f);
        viewer.getCamera().setProjection(60.0, (double) w / h, 0.1, 50.0,
                com.google.android.filament.Camera.Fov.VERTICAL);
    }

    private void updatePilotFromScreenX(float normalizedX) {
        if (viewer == null || destroyed) return;
        updatePilotTransform(viewer.getEngine().getTransformManager(), normalizedX);
    }

    private void startLoop() {
        if (!resumed || running || viewer == null || destroyed) return;
        running = true;
        previousFrameTimeNs = 0L;
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
                if (overlay != null) {
                    overlay.setDiagnostics(statsCallbacks / seconds, statsAccepted / seconds,
                            avgMs, statsRejected);
                }
                statsWindowStartNs = now;
                statsCallbacks = statsAccepted = statsRejected = statsRenderCpuNs = statsMaxRenderCpuNs = 0;
            }
            if (overlay != null) overlay.invalidate();
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
            destroyPilotPart(engine, current, pilotBodyEntity);
            destroyPilotPart(engine, current, pilotHeadEntity);
            destroyPilotPart(engine, current, pilotPackEntity);
            destroyPilotPart(engine, current, pilotLeftArmEntity);
            destroyPilotPart(engine, current, pilotRightArmEntity);
            destroyPilotPart(engine, current, pilotLeftLegEntity);
            destroyPilotPart(engine, current, pilotRightLegEntity);
            if (pilotRootEntity != 0) {
                engine.getTransformManager().destroy(pilotRootEntity);
                EntityManager.get().destroy(pilotRootEntity);
            }
            if (obstacleVertices != null) engine.destroyVertexBuffer(obstacleVertices);
            if (obstacleIndices != null) engine.destroyIndexBuffer(obstacleIndices);

            // Release app-owned lighting while the ModelViewer Engine is still valid.
            if (ambient != null) {
                current.getScene().setIndirectLight(null);
                engine.destroyIndirectLight(ambient);
                ambient = null;
            }
            // Drain queued GPU work before SurfaceView detach lets ModelViewer tear down Engine.
            engine.flushAndWait();
        } catch (Throwable e) {
            Log.w(TAG, "Obstacle cleanup skipped because renderer teardown may already have started", e);
        } finally {
            obstacleEntity = 0;
            pilotRootEntity = 0;
            pilotBodyEntity = pilotHeadEntity = pilotPackEntity = 0;
            pilotLeftArmEntity = pilotRightArmEntity = 0;
            pilotLeftLegEntity = pilotRightLegEntity = 0;
            obstacleVertices = null;
            obstacleIndices = null;
        }
    }

    private void destroyPilotPart(Engine engine, ModelViewer current, int entity) {
        if (entity == 0) return;
        current.getScene().remove(entity);
        engine.getRenderableManager().destroy(entity);
        engine.getTransformManager().destroy(entity);
        EntityManager.get().destroy(entity);
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
        private long touchDownMs;
        private boolean diagnosticsVisible = true;
        private double callbacksPerSecond, acceptedPerSecond, renderCpuMs;
        private long rejectedFrames;
        void setDiagnostics(double callbacks, double accepted, double cpuMs, long rejected) {
            callbacksPerSecond = callbacks; acceptedPerSecond = accepted;
            renderCpuMs = cpuMs; rejectedFrames = rejected;
        }
        PilotOverlay() { super(Stage2IntegrationActivity.this); setBackgroundColor(Color.TRANSPARENT); setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            float x=pilotX*w, y=pilotY*h;
            p.setColor(Color.argb(180,0,229,255)); p.setTextSize(24);
            // Footer is a native TextView above the Filament SurfaceView.
            if (diagnosticsVisible) {
                p.setColor(Color.argb(220, 0, 0, 0));
                c.drawRect(12, 78, 330, 164, p);
                p.setColor(Color.rgb(100, 255, 210)); p.setTextSize(13);
                c.drawText(String.format(java.util.Locale.US, "CALLBACKS/s %.1f", callbacksPerSecond), 22, 100, p);
                c.drawText(String.format(java.util.Locale.US, "ACCEPTED RENDER/s %.1f", acceptedPerSecond), 22, 120, p);
                c.drawText(String.format(java.util.Locale.US, "AVG RENDER CPU %.2f ms", renderCpuMs), 22, 140, p);
                c.drawText("REJECTED " + rejectedFrames + "  •  NOT DISPLAY FPS", 22, 158, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                touchDownMs = e.getEventTime();
                pilotX=Math.max(0f,Math.min(1f,e.getX()/Math.max(1f,getWidth())));
                updatePilotFromScreenX(pilotX);
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                pilotX=Math.max(0f,Math.min(1f,e.getX()/Math.max(1f,getWidth())));
                updatePilotFromScreenX(pilotX);
                invalidate();
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP){
                if (e.getEventTime() - touchDownMs >= 650L) {
                    diagnosticsVisible = !diagnosticsVisible;
                    invalidate();
                }
                performClick(); return true;
            }
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
