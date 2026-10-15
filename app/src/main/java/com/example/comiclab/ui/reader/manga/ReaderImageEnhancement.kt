package com.example.comiclab.ui.reader.manga

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.view.View
import java.util.WeakHashMap

/** A small CAS-style adaptive sharpening pass for rendered archive image pages. */
internal object ReaderImageEnhancement {

    private const val INPUT_SHADER_NAME = "page"

    private val shaderStates = WeakHashMap<View, ShaderState>()

    fun shouldApply(enabled: Boolean, isPdfSource: Boolean): Boolean {
        return enabled && !isPdfSource
    }

    /** Applies the shader only to the supplied image view, never to reader controls or its page parent. */
    fun apply(view: View, enabled: Boolean): Boolean {
        if (!enabled) {
            view.setRenderEffect(null)
            shaderStates[view]?.enabled = false
            return false
        }

        val state = shaderStates[view] ?: createState(view).also { shaderStates[view] = it }
        state.enabled = true
        updateEffect(view, state)
        return true
    }

    /** Releases the per-view shader and its layout callback when a recycled holder is discarded. */
    fun clear(view: View) {
        view.setRenderEffect(null)
        val state = shaderStates.remove(view) ?: return
        view.removeOnLayoutChangeListener(state.layoutChangeListener)
    }

    private fun createState(view: View): ShaderState {
        val shader = RuntimeShader(CAS_STYLE_SHADER)
        val state = ShaderState(shader)
        val layoutChangeListener = View.OnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
            if (state.enabled) {
                updateEffect(changedView, state)
            }
        }
        state.layoutChangeListener = layoutChangeListener
        view.addOnLayoutChangeListener(layoutChangeListener)
        return state
    }

    private fun updateEffect(view: View, state: ShaderState) {
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        if (state.width != width || state.height != height || state.effect == null) {
            updateResolution(state.shader, width, height)
            state.effect = RenderEffect.createRuntimeShaderEffect(state.shader, INPUT_SHADER_NAME)
            state.width = width
            state.height = height
        }
        view.setRenderEffect(state.effect)
    }

    private fun updateResolution(shader: RuntimeShader, width: Int, height: Int) {
        shader.setFloatUniform(
            "resolution",
            width.coerceAtLeast(1).toFloat(),
            height.coerceAtLeast(1).toFloat()
        )
    }

    private class ShaderState(val shader: RuntimeShader) {
        var effect: RenderEffect? = null
        var width = -1
        var height = -1
        var enabled = false
        lateinit var layoutChangeListener: View.OnLayoutChangeListener
    }

    private const val CAS_STYLE_SHADER = """
        uniform shader page;
        uniform float2 resolution;

        half4 samplePage(float2 point) {
            float2 edge = float2(0.5, 0.5);
            float2 maxPoint = max(resolution - edge, edge);
            return page.eval(clamp(point, edge, maxPoint));
        }

        half4 main(float2 coordinate) {
            half4 center = samplePage(coordinate);
            float3 original = float3(center.rgb);
            float3 north = float3(samplePage(coordinate + float2(0.0, -1.0)).rgb);
            float3 south = float3(samplePage(coordinate + float2(0.0, 1.0)).rgb);
            float3 west = float3(samplePage(coordinate + float2(-1.0, 0.0)).rgb);
            float3 east = float3(samplePage(coordinate + float2(1.0, 0.0)).rgb);

            float3 localMin = min(original, min(min(north, south), min(west, east)));
            float3 localMax = max(original, max(max(north, south), max(west, east)));
            float3 headroom = min(localMin, float3(1.0) - localMax);
            float3 adaptiveAmount = sqrt(clamp(
                headroom / max(localMax, float3(0.001)),
                float3(0.0),
                float3(1.0)
            ));

            // A conservative fixed CAS-style strength; contrast limits the sharpening near hard edges.
            float weight = -0.14 * adaptiveAmount.g;
            float3 neighbors = north + south + west + east;
            float3 sharpened = (original + weight * neighbors) / (1.0 + 4.0 * weight);
            return half4(half3(clamp(sharpened, float3(0.0), float3(1.0))), center.a);
        }
    """
}
