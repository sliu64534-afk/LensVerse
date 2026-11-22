package com.example.lutcamera

object LutShader {

    /** 叠加监看开关位。 */
    const val OVERLAY_ZEBRA = 1
    const val OVERLAY_PEAKING = 2
    const val OVERLAY_FALSE_COLOR = 4

    // 顶点着色器：负责绘制画面
    const val VERTEX = """
        #version 300 es

        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;

        uniform mat4 uTextureMatrix;

        out vec2 vTexCoord;

        void main() {
            gl_Position = aPosition;

            vec4 transformedCoord =
                uTextureMatrix * vec4(aTexCoord, 0.0, 1.0);

            vTexCoord = transformedCoord.xy;
        }
    """

    // 片段着色器：LUT 调色 + 专业监看叠加（斑马纹 / 峰值对焦 / 伪色）
    const val FRAGMENT = """
        #version 300 es

        #extension GL_OES_EGL_image_external_essl3 : require

        precision highp float;

        uniform samplerExternalOES uCameraTexture;
        uniform sampler3D uLutTexture;

        uniform float uIntensity;
        uniform vec3 uDomainMin;
        uniform vec3 uDomainMax;

        uniform vec2 uTexelSize;
        uniform float uZebra;
        uniform float uPeaking;
        uniform float uFalseColor;
        uniform float uZebraThreshold;

        in vec2 vTexCoord;
        out vec4 fragColor;

        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

        float lumaAt(vec2 uv) {
            return dot(texture(uCameraTexture, uv).rgb, LUMA);
        }

        void main() {
            vec4 source = texture(uCameraTexture, vTexCoord);

            vec3 range = max(
                uDomainMax - uDomainMin,
                vec3(0.00001)
            );

            vec3 lutInput = (source.rgb - uDomainMin) / range;
            lutInput = clamp(lutInput, 0.0, 1.0);

            vec3 lutColor = texture(
                uLutTexture,
                lutInput
            ).rgb;

            float intensity = clamp(uIntensity, 0.0, 1.0);

            vec3 color = mix(
                source.rgb,
                lutColor,
                intensity
            );

            float luma = dot(color, LUMA);

            if (uFalseColor > 0.5) {
                // 伪色曝光图
                if (luma < 0.02) {
                    color = vec3(0.25, 0.0, 0.55);
                } else if (luma < 0.08) {
                    color = vec3(0.0, 0.15, 1.0);
                } else if (luma < 0.20) {
                    color = vec3(0.0, 0.75, 1.0);
                } else if (luma < 0.45) {
                    color = vec3(0.0, 0.75, 0.15);
                } else if (luma < 0.60) {
                    color = vec3(0.62, 0.62, 0.62);
                } else if (luma < 0.85) {
                    color = vec3(1.0, 0.85, 0.0);
                } else if (luma < 0.95) {
                    color = vec3(1.0, 0.45, 0.0);
                } else {
                    color = vec3(1.0, 0.0, 0.0);
                }
            } else {
                if (uZebra > 0.5 && luma >= uZebraThreshold) {
                    float stripe = step(
                        0.5,
                        fract((gl_FragCoord.x + gl_FragCoord.y) * 0.125)
                    );
                    color = mix(color, vec3(1.0), stripe * 0.85);
                }

                if (uPeaking > 0.5) {
                    float lr = lumaAt(vTexCoord + vec2(uTexelSize.x, 0.0));
                    float ll = lumaAt(vTexCoord - vec2(uTexelSize.x, 0.0));
                    float lu = lumaAt(vTexCoord + vec2(0.0, uTexelSize.y));
                    float ld = lumaAt(vTexCoord - vec2(0.0, uTexelSize.y));

                    float edge = abs(lr - ll) + abs(lu - ld);
                    if (edge > 0.10) {
                        color = mix(
                            color,
                            vec3(1.0, 0.25, 0.25),
                            clamp((edge - 0.10) * 4.0, 0.0, 0.9)
                        );
                    }
                }
            }

            fragColor = vec4(color, source.a);
        }
    """
}
