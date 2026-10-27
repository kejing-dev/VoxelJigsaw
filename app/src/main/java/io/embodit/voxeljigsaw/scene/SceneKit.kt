package io.embodit.voxeljigsaw.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.voxeljigsaw.domain.MeshData
import io.embodit.voxeljigsaw.domain.Quat4
import io.embodit.voxeljigsaw.domain.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// domain <-> SDK types; the domain layer owns the math so it tests on a plain JVM

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Color.color4(alpha: Float = 1f): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Resources shared by entities that come and go: made global, closed when the scene goes away. */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Materials of the scene. Voxels use one lit material per landmark with a 1-row palette texture. */
internal class Materials(private val shared: SharedResources) {
    val limestone: Material = lit(Color(0xFFEDE6D8), 0.8f)
    val ring: Material = lit(Color(0xFFB9AE98), 0.7f)
    val gold: UnlitMaterial = solid(Color(0xFFF2BE4A))
    val goldGhost: UnlitMaterial = sheer(Color(0xFFF2BE4A), 0.30f)
    val shadow: UnlitMaterial = sheer(Color(0xFF000000), 0.22f)
    val sweep: UnlitMaterial = sheer(Color(0xFFF2BE4A), 0.85f)

    fun lit(color: Color, roughness: Float): Material =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                setMetallic(0f)
                setRoughness(roughness)
                // a share of its own colour keeps it from going dull under the room light
                setEmissiveColor(Color4(color.red * 0.25f, color.green * 0.25f, color.blue * 0.25f, 1f))
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun solid(color: Color): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun sheer(color: Color, opacity: Float): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    /** Lit voxel material: base colour and a quarter of it as emission both read the palette texture. */
    fun voxels(palette: List<Int>): Material {
        val tex = shared.keep(TextureResource.create(paletteBitmap(palette)))
        return shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(tex)
                setMetallic(0f)
                setRoughness(0.75f)
                setEmissiveTexture(tex)
                setEmissiveColor(Color4(0.28f, 0.28f, 0.28f, 1f))
                setCullingMode(MaterialCullingMode.NONE)
            }
        )
    }

    /** Voxel colours as a see-through ghost (peek: the whole landmark at 25 %). */
    fun voxelGhost(palette: List<Int>, opacity: Float): UnlitMaterial {
        val tex = shared.keep(TextureResource.create(paletteBitmap(palette)))
        return shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(tex)
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setDepthWrite(false)
            }
        )
    }

    private fun paletteBitmap(palette: List<Int>): Bitmap {
        val bmp = Bitmap.createBitmap(palette.size, 1, Bitmap.Config.ARGB_8888)
        palette.forEachIndexed { i, c -> bmp.setPixel(i, 0, c) }
        return bmp
    }
}

/** Builds SDK meshes. */
internal object Meshes {
    fun fromData(data: MeshData, name: String): MeshResource {
        val n = data.vertexCount
        val positions = ArrayList<Vector3>(n)
        val normals = ArrayList<Vector3>(n)
        val uvs = ArrayList<Vector2>(n)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = data.positions[i * 3]; val y = data.positions[i * 3 + 1]; val z = data.positions[i * 3 + 2]
            positions += Vector3(x, y, z)
            normals += Vector3(data.normals[i * 3], data.normals[i * 3 + 1], data.normals[i * 3 + 2])
            uvs += Vector2(data.uvs[i * 2], data.uvs[i * 2 + 1])
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        val bounds = BoundingBox(center = Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2), halfExtent = Vector3(max(0.001f, (maxX - minX) / 2), max(0.001f, (maxY - minY) / 2), max(0.001f, (maxZ - minZ) / 2)))
        val model = MeshModel(positions = positions, triangleIndices = data.indices.toList(), normals = normals, uv0 = uvs)
        return MeshResource.createWithMeshModel(model = model, bounds = bounds, name = name)
    }

    /** A cylinder of diameter 1 and height 1 about local Y (the turntable). */
    fun cylinder(name: String, seg: Int = 64): MeshResource {
        val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
            pos += x; pos += y; pos += z; nor += nx; nor += ny; nor += nz; uv += 0.5f; uv += 0.5f
            return pos.size / 3 - 1
        }
        val top = v(0f, 0.5f, 0f, 0f, 1f, 0f)
        val bottom = v(0f, -0.5f, 0f, 0f, -1f, 0f)
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg; val a1 = 2 * PI * (i + 1) / seg
            val x0 = (0.5 * cos(a0)).toFloat(); val z0 = (0.5 * sin(a0)).toFloat()
            val x1 = (0.5 * cos(a1)).toFloat(); val z1 = (0.5 * sin(a1)).toFloat()
            idx += top; idx += v(x1, 0.5f, z1, 0f, 1f, 0f); idx += v(x0, 0.5f, z0, 0f, 1f, 0f)
            idx += bottom; idx += v(x0, -0.5f, z0, 0f, -1f, 0f); idx += v(x1, -0.5f, z1, 0f, -1f, 0f)
            val a = v(x0, -0.5f, z0, x0 * 2, 0f, z0 * 2); val b = v(x1, -0.5f, z1, x1 * 2, 0f, z1 * 2)
            val c = v(x1, 0.5f, z1, x1 * 2, 0f, z1 * 2); val d = v(x0, 0.5f, z0, x0 * 2, 0f, z0 * 2)
            idx += a; idx += c; idx += b; idx += a; idx += d; idx += c
        }
        return fromData(MeshData(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), idx.toIntArray()), name)
    }

    /** A flat ring in the XZ plane facing +Y between radii [inner] and 0.5. */
    fun ring(name: String, inner: Float, seg: Int = 72): MeshResource {
        val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            for (r in floatArrayOf(inner, 0.5f)) {
                pos += (r * cos(a)).toFloat(); pos += 0f; pos += (r * sin(a)).toFloat()
                nor += 0f; nor += 1f; nor += 0f; uv += 0.5f; uv += 0.5f
            }
        }
        for (i in 0 until seg) {
            val a = i * 2; val b = i * 2 + 1; val c = i * 2 + 3; val d = i * 2 + 2
            idx += a; idx += c; idx += b; idx += a; idx += d; idx += c
        }
        return fromData(MeshData(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), idx.toIntArray()), name)
    }

    /** A flat disc of diameter 1 in XZ facing +Y. */
    fun disc(name: String, seg: Int = 40): MeshResource {
        val pos = arrayListOf(0f, 0f, 0f); val nor = arrayListOf(0f, 1f, 0f); val uv = arrayListOf(0.5f, 0.5f); val idx = ArrayList<Int>()
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            pos += (0.5 * cos(a)).toFloat(); pos += 0f; pos += (0.5 * sin(a)).toFloat()
            nor += 0f; nor += 1f; nor += 0f; uv += 0.5f; uv += 0.5f
        }
        for (i in 1..seg) { idx += 0; idx += i + 1; idx += i }
        return fromData(MeshData(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), idx.toIntArray()), name)
    }

    fun cube(): MeshResource = MeshResource.createBox(Vector3(1f, 1f, 1f), 0f)
}

internal fun model(mesh: MeshResource, material: Material, name: String, parent: Entity): ModelEntity =
    ModelEntity(mesh, material).also {
        runCatching { it.setName(name) }
        parent.addChild(it)
    }

/**
 * A gold frame of 12 thin bars around a box (the selection outline and the breathing hint). It is re-parented
 * to whichever piece it marks; [fit] sizes it to that piece's box.
 */
internal class Outline(cube: MeshResource, material: Material, name: String) {
    val root = Entity().named(name)
    private val bars = List(12) { model(cube, material, "$name:bar$it", root) }

    fun fit(half: Vec3, thickness: Float = 0.0025f) {
        val h = half + Vec3(0.003f, 0.003f, 0.003f)
        var i = 0
        for (a in 0 until 3) {
            val u = (a + 1) % 3
            val w = (a + 2) % 3
            for (su in intArrayOf(-1, 1)) for (sw in intArrayOf(-1, 1)) {
                val c = FloatArray(3); val s = FloatArray(3)
                c[u] = su * h.comp(u); c[w] = sw * h.comp(w)
                s[a] = h.comp(a) * 2 + thickness; s[u] = thickness; s[w] = thickness
                bars[i++].setLocal(position = Vec3(c[0], c[1], c[2]), scale = Vec3(s[0], s[1], s[2]))
            }
        }
    }

    private fun Vec3.comp(i: Int) = when (i) { 0 -> x; 1 -> y; else -> z }
}
