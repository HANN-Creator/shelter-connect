package org.shelterconnect.api.asset;

public enum AssetAction {
    BASE(0, false, ""),
    IDLE(140, true, "Stand in place with subtle breathing, one gentle blink and a small head glance. All paws stay planted. Seamless loop."),
    WALK(75, true, "Walk in place with a natural alternating four-legged canine gait. No sideways travel. Seamless loop."),
    RUN(55, true, "Run in place with a coordinated four-legged canine gallop, flexing front and hind legs. No sideways travel. Seamless loop."),
    SNIFF(100, true, "Lower the head to sniff the ground near the front paws, then return to the starting pose. Paws stay planted. Seamless loop."),
    TAIL_WAG(90, true, "Gently wag only the tail side to side. All four paws planted, body and head stable. Subtle seamless loop."),
    BACK_OFF(90, true, "Take small cautious backward steps in place, still looking right. Alternate paws without translating across the canvas. Seamless backward walk loop."),
    SIT(90, false, "Bend the hind legs and lower the hips into a natural seated dog pose. Front paws planted. End seated and hold for the last two frames. Do not stand again."),
    LIE_DOWN(100, false, "Bend front and hind legs and lower chest and belly to rest on the ground. End lying down and hold for the last two frames. Do not stand again.");

    public static final String VERSION = "pixellab-harness-v3";
    final int durationMs;
    final boolean loop;
    private final String motion;
    AssetAction(int durationMs, boolean loop, String motion) { this.durationMs=durationMs; this.loop=loop; this.motion=motion; }
    String prompt() {
        return motion + " Same puppy identity as the first frame, four legs, same muzzle, ears, coat markings and palette."
            + " Facing screen right in three-quarter view. Locked camera and scale, shared ground line."
            + " Transparent pixel art. No extra limbs, props, text, turning or zoom.";
    }
    static final String BASE_PROMPT = "Create ONE full-body dog sprite for a cozy pixel-art shelter game. PHOTO reference images and visible features define identity; "
        + "preserve coat, markings, ears, muzzle and tail, ignoring photo pose, poster text, people, background and props. "
        + "Stand on FOUR planted paws, closed mouth, elevated three-quarter view facing RIGHT, head right and tail left. "
        + "Cute natural proportions with a modestly enlarged head. STYLE reference defines crisp pixel clusters, outline and shading ONLY; never copy its colors or identity. "
        + "Native 64x64, restrained subject-derived palette, warm gray-brown outline, no anti-aliasing. "
        + "Keep 6px side and 4px top margins, transparent background. No cropped parts, floor, cast shadow, collar, text, extra legs or duplicate dogs.";
}
