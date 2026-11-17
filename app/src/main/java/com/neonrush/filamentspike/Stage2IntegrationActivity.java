package com.neonrush.filamentspike;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
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
import com.google.android.filament.Material;
import com.google.android.filament.MaterialInstance;
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
    private static final float OBSTACLE_START_Z = -10.0f;
    private static final float OBSTACLE_RECYCLE_Z = -2.0f;
    private static final float OBSTACLE_SPEED_UNITS_PER_SECOND = 2.0f;
    private static final float PILOT_Z = -4.0f;
    private static final float PILOT_HALF_WIDTH = 0.40f;
    private static final float PILOT_HALF_HEIGHT = 0.815f;
    private static final float PILOT_HALF_DEPTH = 0.32f;
    private static final float BASE_HALF_X = 0.325f, BASE_HALF_Y = 0.225f, BASE_HALF_Z = 0.125f;
    private static final float OBSTACLE_CENTER_X = 0.65f;
    private static final float OBSTACLE_CENTER_Y = BASE_HALF_Y;
    private static final float OBSTACLE_HALF_X = BASE_HALF_X, OBSTACLE_HALF_Y = BASE_HALF_Y, OBSTACLE_HALF_Z = BASE_HALF_Z;

    private FrameLayout root;
    private SurfaceView surface;
    private ModelViewer viewer;
    private IndirectLight ambient;
    private boolean resumed;
    private boolean running;
    private boolean destroyed;
    private boolean initializing;
    private PilotOverlay overlay;
    private int perfProfile = 0;
    private static final String[] PERF_PROFILE_NAMES = {
            "FULL BASELINE", "BLOOM OFF", "MSAA OFF", "FXAA OFF",
            "DYNAMIC 75-100%", "FIXED 75%", "FIXED 65%",
            "CORRIDOR ONLY", "CORRIDOR + PILOT", "CORRIDOR + OBSTACLE"
    };

    // One reusable Filament renderable: geometry is built once; only its transform moves.
    private int obstacleEntity;
    private int pilotRootEntity;
    private int pilotBodyEntity, pilotHeadEntity, pilotPackEntity;
    private int pilotLeftArmEntity, pilotRightArmEntity, pilotLeftLegEntity, pilotRightLegEntity;
    private float pilotWorldX;
    private float pilotWorldY;
    private boolean obstacleCollisionReported;
    private VertexBuffer obstacleVertices;
    private Material blockoutMaterial;
    private MaterialInstance pilotMaterialInstance;
    private MaterialInstance obstacleMaterialInstance;
    private IndexBuffer obstacleIndices;
    private float obstacleZ = OBSTACLE_START_Z;
    private long previousFrameTimeNs;
    private long statsWindowStartNs;
    private long statsCallbacks;
    private long statsAccepted;
    private long statsRejected;
    private long statsObstacleUpdateCpuNs;
    private long statsRenderCallCpuNs;
    private long statsMaxRenderCallCpuNs;
    private long lastCallbackFrameTimeNs;
    private long statsCallbackIntervalNs;
    private long statsMaxCallbackIntervalNs;
    private long statsCallbackGaps;
    private double displayRefreshHz = 60.0;
    private String thermalStatusName = "UNKNOWN";
    private boolean powerSaveMode;

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

            createBlockoutMaterials(engine);
            byte[] encoded = readAsset("corridor.glb.b64");
            byte[] glb = Base64.decode(new String(encoded, java.nio.charset.StandardCharsets.US_ASCII), Base64.DEFAULT);
            viewer.loadModelGlb(ByteBuffer.wrap(glb));
            create3DObstacle(engine);
            create3DPilot(engine);
            applyPerfProfile();
            applyCamera();
        } catch (Throwable e) {
            Log.e(TAG, "Renderer initialization failed", e);
            stopLoop();
        } finally {
            initializing = false;
        }
        if (resumed) startLoop();
    }

    // Each profile is an isolated comparison against FULL BASELINE.
    // FULL BASELINE = bloom + 2x MSAA + FXAA, fixed native resolution,
    // SSAO/shadows off, and both synthetic renderables visible.
    private void applyPerfProfile() {
        if (viewer == null) return;
        View view = viewer.getView();

        boolean bloomEnabled = perfProfile != 1;
        boolean msaaEnabled = perfProfile != 2;
        boolean fxaaEnabled = perfProfile != 3;
        boolean dynamicEnabled = perfProfile == 4 || perfProfile == 5 || perfProfile == 6;
        float minScale = perfProfile == 4 ? 0.75f : (perfProfile == 6 ? 0.65f : 0.75f);
        float maxScale = perfProfile == 4 ? 1.00f : (perfProfile == 5 ? 0.75f : (perfProfile == 6 ? 0.65f : 1.00f));

        View.BloomOptions bloom = new View.BloomOptions();
        bloom.enabled = bloomEnabled;
        bloom.strength = 0.24f;
        bloom.resolution = 192;
        bloom.levels = 4;
        bloom.threshold = true;
        bloom.quality = View.QualityLevel.LOW;
        bloom.lensFlare = false;
        view.setBloomOptions(bloom);

        View.MultiSampleAntiAliasingOptions msaa = new View.MultiSampleAntiAliasingOptions();
        msaa.enabled = msaaEnabled;
        msaa.sampleCount = 2;
        msaa.customResolve = false;
        view.setMultiSampleAntiAliasingOptions(msaa);
        view.setAntiAliasing(fxaaEnabled ? View.AntiAliasing.FXAA : View.AntiAliasing.NONE);

        View.DynamicResolutionOptions dynamic = new View.DynamicResolutionOptions();
        dynamic.enabled = dynamicEnabled;
        dynamic.minScale = minScale;
        dynamic.maxScale = maxScale;
        dynamic.homogeneousScaling = true;
        dynamic.quality = View.QualityLevel.LOW;
        view.setDynamicResolutionOptions(dynamic);

        View.AmbientOcclusionOptions ao = new View.AmbientOcclusionOptions();
        ao.enabled = false;
        view.setAmbientOcclusionOptions(ao);
        view.setShadowingEnabled(false);

        boolean showPilot = perfProfile != 7 && perfProfile != 9;
        boolean showObstacle = perfProfile != 7 && perfProfile != 8;
        int[] pilotParts = {pilotBodyEntity, pilotHeadEntity, pilotPackEntity,
                pilotLeftArmEntity, pilotRightArmEntity, pilotLeftLegEntity, pilotRightLegEntity};
        for (int entity : pilotParts) {
            if (entity == 0) continue;
            if (showPilot) viewSceneAdd(entity); else viewSceneRemove(entity);
        }
        if (obstacleEntity != 0) {
            if (showObstacle) viewSceneAdd(obstacleEntity); else viewSceneRemove(obstacleEntity);
        }
        if (overlay != null) {
            overlay.setPerfProfile(PERF_PROFILE_NAMES[perfProfile], activeSettingsLine1(),
                    activeSettingsLine2(), activeSettingsLine3());
        }
    }

    private String activeSettingsLine1() {
        return "BLOOM " + (perfProfile == 1 ? "OFF" : "ON")
                + " | MSAA " + (perfProfile == 2 ? "OFF" : "2X")
                + " | FXAA " + (perfProfile == 3 ? "OFF" : "ON");
    }

    private String activeSettingsLine2() {
        if (perfProfile == 4) return "DYNAMIC 75-100% | SSAO OFF | SHADOWS OFF";
        if (perfProfile == 5) return "FIXED 75% | SSAO OFF | SHADOWS OFF";
        if (perfProfile == 6) return "FIXED 65% | SSAO OFF | SHADOWS OFF";
        return "DYNAMIC OFF 100% | SSAO OFF | SHADOWS OFF";
    }

    private String activeSettingsLine3() {
        boolean showPilot = perfProfile != 7 && perfProfile != 9;
        boolean showObstacle = perfProfile != 7 && perfProfile != 8;
        return "PILOT " + (showPilot ? "ON" : "OFF")
                + " | OBSTACLE " + (showObstacle ? "ON" : "OFF");
    }

    private void updateDeviceConditions() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            powerSaveMode = pm.isPowerSaveMode();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalStatusName = thermalStatusToName(pm.getCurrentThermalStatus());
            } else {
                thermalStatusName = "API<29";
            }
        } else {
            thermalStatusName = "UNAVAILABLE";
            powerSaveMode = false;
        }
        if (surface != null && surface.getDisplay() != null) {
            displayRefreshHz = surface.getDisplay().getRefreshRate();
        }
    }

    private static String thermalStatusToName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "NONE";
            case PowerManager.THERMAL_STATUS_LIGHT: return "LIGHT";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MODERATE";
            case PowerManager.THERMAL_STATUS_SEVERE: return "SEVERE";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "CRITICAL";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "EMERGENCY";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "SHUTDOWN";
            default: return "UNKNOWN(" + status + ")";
        }
    }

    private void resetPerfWindow() {
        statsWindowStartNs = System.nanoTime();
        statsCallbacks = statsAccepted = statsRejected = 0;
        statsObstacleUpdateCpuNs = statsRenderCallCpuNs = statsMaxRenderCallCpuNs = 0;
        statsCallbackIntervalNs = statsMaxCallbackIntervalNs = statsCallbackGaps = 0;
        lastCallbackFrameTimeNs = 0L;
    }

    private void viewSceneAdd(int entity) {
        if (viewer != null) viewer.getScene().addEntity(entity);
    }
    private void viewSceneRemove(int entity) {
        if (viewer != null) viewer.getScene().remove(entity);
    }


    // One compact, precompiled Filament material is shared by two tuned instances.
    // The compiler runs in GitHub Actions; no shader compiler is shipped in the APK.
    private void createBlockoutMaterials(Engine engine) throws Exception {
        ByteBuffer payload = ByteBuffer.wrap(readAsset("blockout_pbr.filamat"));
        blockoutMaterial = new Material.Builder()
                .payload(payload, payload.remaining())
                .build(engine);

        pilotMaterialInstance = blockoutMaterial.createInstance("Stage2PilotGraphite");
        pilotMaterialInstance.setParameter("baseColor", 0.105f, 0.135f, 0.16f, 1.0f);
        pilotMaterialInstance.setParameter("metallic", 0.56f);
        pilotMaterialInstance.setParameter("roughness", 0.46f);
        pilotMaterialInstance.setParameter("emissive", 0.003f, 0.022f, 0.030f, 1.0f);

        obstacleMaterialInstance = blockoutMaterial.createInstance("Stage2ObstacleAmber");
        obstacleMaterialInstance.setParameter("baseColor", 0.19f, 0.078f, 0.025f, 1.0f);
        obstacleMaterialInstance.setParameter("metallic", 0.28f);
        obstacleMaterialInstance.setParameter("roughness", 0.58f);
        obstacleMaterialInstance.setParameter("emissive", 0.055f, 0.018f, 0.002f, 1.0f);
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
                .material(0, obstacleMaterialInstance)
                .build(engine, obstacleEntity);

        TransformManager transforms = engine.getTransformManager();
        transforms.create(obstacleEntity);
        // Center at half-height so the obstacle's bottom sits on the y=0 floor plane.
        setObstacleTransform(transforms, OBSTACLE_CENTER_X, OBSTACLE_CENTER_Y, obstacleZ);
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
        updatePilotTransform(engine.getTransformManager(), 0.5f, 0.56f);
    }

    private int createPilotPart(Engine engine) {
        int entity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .geometryType(RenderableManager.Builder.GeometryType.DYNAMIC)
                .boundingBox(new Box(0f, 0f, 0f, BASE_HALF_X, BASE_HALF_Y, BASE_HALF_Z))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                        obstacleVertices, obstacleIndices, 0, 36)
                .material(0, pilotMaterialInstance)
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

    private void updatePilotTransform(TransformManager transforms, float normalizedX, float normalizedY) {
        float depth = 2.0f - PILOT_Z;
        float aspect = surface.getHeight() > 0 ? (float) surface.getWidth() / (float) surface.getHeight() : 0.5f;
        float visibleHalfWidth = depth * 0.57735027f * aspect;
        float requestedX = (normalizedX * 2f - 1f) * visibleHalfWidth;
        float safeCenterLimit = Math.max(0f, visibleHalfWidth - PILOT_HALF_WIDTH - 0.04f);
        pilotWorldX = Math.max(-safeCenterLimit, Math.min(safeCenterLimit, requestedX));

        // Map finger Y onto the pilot's fixed-Z plane using the current camera geometry.
        float ndcY = 1f - 2f * Math.max(0f, Math.min(1f, normalizedY));
        float invLength = 1f / (float)Math.sqrt(11f * 11f + 0.6f * 0.6f);
        float rayY = -0.6f * invLength + 11f * invLength * ndcY * 0.57735027f;
        float rayZ = -11f * invLength - 0.6f * invLength * ndcY * 0.57735027f;
        float distance = (PILOT_Z - 2f) / rayZ;
        float requestedRootY = 1.6f + rayY * distance - PILOT_HALF_HEIGHT;

        // Keep the COMPLETE pilot inside the camera's visible vertical frustum.
        // Root Y is the pilot's foot/base height; local geometry spans 0..1.63.
        // Derive the upper bound at the pilot depth rather than using an
        // arbitrary large value that lets the head disappear above the screen.
        float visibleTopY = 1.6f + depth * 0.57735027f;
        float pilotFullHeight = PILOT_HALF_HEIGHT * 2.0f;
        float topSafetyMargin = 0.18f;
        float maxPilotRootY = Math.max(0f, visibleTopY - pilotFullHeight - topSafetyMargin);
        pilotWorldY = Math.max(0f, Math.min(maxPilotRootY, requestedRootY));

        int rootInstance = transforms.getInstance(pilotRootEntity);
        float[] rootMatrix = {1,0,0,0, 0,1,0,0, 0,0,1,0, pilotWorldX,pilotWorldY,PILOT_Z,1};
        transforms.setTransform(rootInstance, rootMatrix);
        setPilotPart(transforms, pilotBodyEntity, 0f, 0.95f, 0f, 0.23f, 0.34f, 0.16f);
        setPilotPart(transforms, pilotHeadEntity, 0f, 1.43f, 0f, 0.18f, 0.20f, 0.18f);
        setPilotPart(transforms, pilotPackEntity, 0f, 0.92f, 0.20f, 0.18f, 0.28f, 0.12f);
        setPilotPart(transforms, pilotLeftArmEntity, -0.29f, 0.94f, 0f, 0.09f, 0.28f, 0.10f);
        setPilotPart(transforms, pilotRightArmEntity, 0.29f, 0.94f, 0f, 0.09f, 0.28f, 0.10f);
        setPilotPart(transforms, pilotLeftLegEntity, -0.12f, 0.30f, 0f, 0.105f, 0.30f, 0.12f);
        setPilotPart(transforms, pilotRightLegEntity, 0.12f, 0.30f, 0f, 0.105f, 0.30f, 0.12f);
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
        float previousObstacleZ = obstacleZ;
        obstacleZ += OBSTACLE_SPEED_UNITS_PER_SECOND * deltaSeconds;
        if (!obstacleCollisionReported && overlapsPilotObstacleAabb(previousObstacleZ, obstacleZ)) {
            Log.i(TAG, "TEST_COLLISION_AABB: pilot and obstacle world-space bounds overlap");
            obstacleCollisionReported = true;
        }
        if (!obstacleCollisionReported && obstacleZ > PILOT_Z + 0.42f) {
            Log.i(TAG, "TEST_PASS_AABB: obstacle cleared pilot bounds");
            obstacleCollisionReported = true;
        }
        if (obstacleZ > OBSTACLE_RECYCLE_Z) {
            obstacleZ = OBSTACLE_START_Z;
            obstacleCollisionReported = false;
        }
        setObstacleTransform(viewer.getEngine().getTransformManager(), OBSTACLE_CENTER_X, OBSTACLE_CENTER_Y, obstacleZ);
    }

    // Conservative world-space AABB test using the assembled pilot extents and obstacle bounds.
    private boolean overlapsPilotObstacleAabb(float previousZ, float currentZ) {
        float pilotMinY = pilotWorldY;
        float pilotMaxY = pilotWorldY + 2f * PILOT_HALF_HEIGHT;
        boolean x = Math.abs(pilotWorldX - OBSTACLE_CENTER_X) <= PILOT_HALF_WIDTH + OBSTACLE_HALF_X;
        boolean y = pilotMaxY >= OBSTACLE_CENTER_Y - OBSTACLE_HALF_Y
                && pilotMinY <= OBSTACLE_CENTER_Y + OBSTACLE_HALF_Y;
        float sweptMinZ = Math.min(previousZ, currentZ);
        float sweptMaxZ = Math.max(previousZ, currentZ);
        boolean z = sweptMaxZ >= PILOT_Z - (PILOT_HALF_DEPTH + OBSTACLE_HALF_Z)
                && sweptMinZ <= PILOT_Z + (PILOT_HALF_DEPTH + OBSTACLE_HALF_Z);
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
        viewer.getCamera().lookAt(0, 1.6, 2, 0, 1.6, -9, 0, 1, 0);
        viewer.setCameraNear(0.1f); viewer.setCameraFar(50f);
        viewer.getCamera().setProjection(60.0, (double) w / h, 0.1, 50.0,
                com.google.android.filament.Camera.Fov.VERTICAL);
    }

    private void updatePilotFromScreen(float normalizedX, float normalizedY) {
        if (viewer == null || destroyed) return;
        // Keep the header and footer clear; the entire intervening playfield is steerable.
        float playY = Math.max(0.12f, Math.min(0.78f, normalizedY));
        float mappedY = (playY - 0.12f) / 0.66f;
        updatePilotTransform(viewer.getEngine().getTransformManager(), normalizedX, mappedY);
    }

    private void startLoop() {
        if (!resumed || running || viewer == null || destroyed) return;
        running = true;
        previousFrameTimeNs = 0L;
        updateDeviceConditions();
        resetPerfWindow();
        Choreographer.getInstance().postFrameCallback(this);
    }
    private void stopLoop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }
    @Override public void doFrame(long frameTimeNanos) {
        ModelViewer current = viewer;
        if (!running || !resumed || current == null || destroyed) return;
        long updateStartNs = System.nanoTime();
        try {
            statsCallbacks++;
            if (lastCallbackFrameTimeNs != 0L) {
                long callbackInterval = frameTimeNanos - lastCallbackFrameTimeNs;
                statsCallbackIntervalNs += callbackInterval;
                if (callbackInterval > statsMaxCallbackIntervalNs) statsMaxCallbackIntervalNs = callbackInterval;
                double expectedPeriodNs = displayRefreshHz > 0.0 ? 1_000_000_000.0 / displayRefreshHz : 16_666_667.0;
                if (callbackInterval > expectedPeriodNs * 1.5) statsCallbackGaps++;
            }
            lastCallbackFrameTimeNs = frameTimeNanos;
            update3DObstacle(frameTimeNanos);
            statsObstacleUpdateCpuNs += System.nanoTime() - updateStartNs;
            long renderStartNs = System.nanoTime();
            boolean accepted = current.render(frameTimeNanos);
            long renderCallCpuNs = System.nanoTime() - renderStartNs;
            statsRenderCallCpuNs += renderCallCpuNs;
            if (renderCallCpuNs > statsMaxRenderCallCpuNs) statsMaxRenderCallCpuNs = renderCallCpuNs;
            if (accepted) statsAccepted++; else statsRejected++;

            long now = System.nanoTime();
            if (now - statsWindowStartNs >= 1_000_000_000L) {
                double seconds = (now - statsWindowStartNs) / 1_000_000_000.0;
                double avgUpdateMs = statsCallbacks == 0 ? 0 : statsObstacleUpdateCpuNs / 1_000_000.0 / statsCallbacks;
                double avgRenderCallMs = statsCallbacks == 0 ? 0 : statsRenderCallCpuNs / 1_000_000.0 / statsCallbacks;
                double avgCallbackMs = statsCallbacks < 2 ? 0
                        : statsCallbackIntervalNs / 1_000_000.0 / (statsCallbacks - 1);
                updateDeviceConditions();
                String profileSettings = activeSettingsLine1() + " | " + activeSettingsLine2()
                        + " | " + activeSettingsLine3();
                Log.i(TAG, String.format(java.util.Locale.US,
                        "PERF profile=%s settings=[%s] thermal=%s powerSave=%s displayHz=%.1f callbacks/s=%.1f acceptedRender/s=%.1f false=%d callbackAvgMs=%.2f callbackMaxMs=%.2f callbackGaps=%d avgUpdateCPUms=%.3f avgRenderCallCPUms=%.3f maxRenderCallCPUms=%.3f",
                        PERF_PROFILE_NAMES[perfProfile], profileSettings, thermalStatusName, powerSaveMode,
                        displayRefreshHz, statsCallbacks / seconds, statsAccepted / seconds, statsRejected,
                        avgCallbackMs, statsMaxCallbackIntervalNs / 1_000_000.0, statsCallbackGaps,
                        avgUpdateMs, avgRenderCallMs, statsMaxRenderCallCpuNs / 1_000_000.0));
                if (overlay != null) overlay.setDiagnostics(statsCallbacks / seconds, statsAccepted / seconds,
                        avgUpdateMs, avgRenderCallMs, statsRejected, avgCallbackMs,
                        statsCallbackGaps, thermalStatusName, powerSaveMode, displayRefreshHz);
                resetPerfWindow();
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
        if (current == null) return;
        try {
            Engine engine = current.getEngine();
            destroyOwnedEntity(engine, current, obstacleEntity);
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
            if (pilotMaterialInstance != null) engine.destroyMaterialInstance(pilotMaterialInstance);
            if (obstacleMaterialInstance != null) engine.destroyMaterialInstance(obstacleMaterialInstance);
            if (blockoutMaterial != null) engine.destroyMaterial(blockoutMaterial);
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
            pilotMaterialInstance = null;
            obstacleMaterialInstance = null;
            blockoutMaterial = null;
        }
    }

    private void destroyPilotPart(Engine engine, ModelViewer current, int entity) {
        destroyOwnedEntity(engine, current, entity);
    }

    private void destroyOwnedEntity(Engine engine, ModelViewer current, int entity) {
        if (entity == 0) return;
        try { current.getScene().remove(entity); } catch (Throwable ignored) { }
        try { engine.getRenderableManager().destroy(entity); } catch (Throwable ignored) { }
        try { engine.getTransformManager().destroy(entity); } catch (Throwable ignored) { }
        try { EntityManager.get().destroy(entity); } catch (Throwable ignored) { }
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
        private float touchDownX, touchDownY;
        private boolean touchMoved;
        private boolean diagnosticsVisible = true;
        private double callbacksPerSecond, acceptedPerSecond, updateCpuMs, renderCallCpuMs;
        private double avgCallbackMs, displayHz;
        private long rejectedFrames, callbackGaps;
        private String thermalName = "UNKNOWN";
        private boolean powerSave;
        private String perfProfileName = "FULL BASELINE";
        private String settingsLine1 = "", settingsLine2 = "", settingsLine3 = "";
        void setPerfProfile(String name, String line1, String line2, String line3) {
            perfProfileName = name;
            settingsLine1 = line1; settingsLine2 = line2; settingsLine3 = line3;
            invalidate();
        }
        void setDiagnostics(double callbacks, double accepted, double updateMs, double renderCallMs,
                            long rejected, double callbackMs, long gaps, String thermal,
                            boolean saveMode, double refreshHz) {
            callbacksPerSecond = callbacks; acceptedPerSecond = accepted;
            updateCpuMs = updateMs; renderCallCpuMs = renderCallMs; rejectedFrames = rejected;
            avgCallbackMs = callbackMs; callbackGaps = gaps; thermalName = thermal;
            powerSave = saveMode; displayHz = refreshHz;
        }
        PilotOverlay() { super(Stage2IntegrationActivity.this); setBackgroundColor(Color.TRANSPARENT); setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            float x=pilotX*w, y=pilotY*h;
            p.setColor(Color.argb(180,0,229,255)); p.setTextSize(24);
            // Footer is a native TextView above the Filament SurfaceView.
            if (diagnosticsVisible) {
                p.setColor(Color.argb(224, 0, 0, 0));
                c.drawRect(12, 78, 510, 306, p);
                p.setTextSize(11.5f);
                p.setColor(Color.rgb(100, 255, 210));
                c.drawText(String.format(java.util.Locale.US, "CALLBACKS/s %.1f", callbacksPerSecond), 22, 98, p);
                c.drawText(String.format(java.util.Locale.US, "ACCEPTED RENDER/s %.1f", acceptedPerSecond), 22, 116, p);
                c.drawText(String.format(java.util.Locale.US, "AVG UPDATE CPU %.3f ms", updateCpuMs), 22, 134, p);
                c.drawText(String.format(java.util.Locale.US, "AVG RENDER CALL CPU %.3f ms", renderCallCpuMs), 22, 152, p);
                c.drawText("MODELVIEWER FALSE / 1s: " + rejectedFrames + "  •  NOT DISPLAY FPS", 22, 170, p);
                p.setColor(Color.WHITE);
                c.drawText("PROFILE " + perfProfileName + "  •  LONG-PRESS TO CHANGE", 22, 190, p);
                p.setColor(Color.rgb(180, 225, 255));
                c.drawText(settingsLine1, 22, 210, p);
                c.drawText(settingsLine2, 22, 228, p);
                c.drawText(settingsLine3, 22, 246, p);
                p.setColor(Color.rgb(255, 220, 145));
                c.drawText("THERMAL " + thermalName + " | POWER SAVE " + (powerSave ? "ON" : "OFF")
                        + " | DISPLAY " + String.format(java.util.Locale.US, "%.1fHz", displayHz), 22, 266, p);
                c.drawText(String.format(java.util.Locale.US, "CALLBACK AVG %.2fms | GAPS >1.5x VSYNC %d",
                        avgCallbackMs, callbackGaps), 22, 286, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                touchDownMs = e.getEventTime();
                touchDownX = e.getX();
                touchDownY = e.getY();
                touchMoved = false;
                pilotX=Math.max(0f,Math.min(1f,e.getX()/Math.max(1f,getWidth())));
                pilotY=Math.max(0f,Math.min(1f,e.getY()/Math.max(1f,getHeight())));
                updatePilotFromScreen(pilotX, pilotY);
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                if (Math.abs(e.getX() - touchDownX) > 24f || Math.abs(e.getY() - touchDownY) > 24f)
                    touchMoved = true;
                pilotX=Math.max(0f,Math.min(1f,e.getX()/Math.max(1f,getWidth())));
                pilotY=Math.max(0f,Math.min(1f,e.getY()/Math.max(1f,getHeight())));
                updatePilotFromScreen(pilotX, pilotY);
                invalidate();
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP){
                if (!touchMoved && e.getEventTime() - touchDownMs >= 650L) {
                    perfProfile = (perfProfile + 1) % PERF_PROFILE_NAMES.length;
                    applyPerfProfile();
                    resetPerfWindow();
                    diagnosticsVisible = true;
                    invalidate();
                }
                performClick(); return true;
            }
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
