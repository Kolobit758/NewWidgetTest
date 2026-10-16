"""Import the supplied PNG sequences. Requires Pillow; originals remain untouched."""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SEQUENCES = (
    ("Cati_Groom_new", "cati_groom_new", 18, True),
    ("Cati_Stretch_new1", "cati_stretch_new", 12, True),
    ("Cati_Roll_new", "cati_roll_new", 12, True),
    ("Cati_Jumpup_new", "cati_jump_new", 12, False),
    ("Cati_Nap_new", "cati_nap_new", 12, True),
    ("Cati_Playball_new", "cati_playball_new", 8, True),
    # The numbered exports' folder labels are reversed: use the actual facing poses.
    ("Cati_Idle_to_Walk_&_Walk_to_Idle/Cati_Walk_to_Idle", "cati_idle_to_walk_new", 3, True),
    ("Cati_Idle_to_Walk_&_Walk_to_Idle/Cati_Idle_to_Walk", "cati_walk_to_idle_new", 3, True),
)


def import_sequence(source, destination, expected, grounded):
    paths = sorted(source.glob("[0-9][0-9]_*.png"))
    assert len(paths) == expected, (source, len(paths), expected)
    images = [Image.open(path).convert("RGBA") for path in paths]
    assert len({image.size for image in images}) == 1, source
    for image in images:
        # Exports contain nearly transparent specks at the canvas edges.
        image.putalpha(image.getchannel("A").point(lambda alpha: alpha if alpha > 32 else 0))
    bounds = [image.getchannel("A").getbbox() for image in images]
    assert all(bounds), source
    left = min(box[0] for box in bounds)
    top = min(box[1] for box in bounds)
    right = max(box[2] for box in bounds)
    bottom = max(box[3] for box in bounds)
    padding = 4
    size = (right - left + padding * 2, bottom - top + padding * 2)
    destination.mkdir(parents=True, exist_ok=True)
    frames = []
    for index, (image, box) in enumerate(zip(images, bounds)):
        # Fixed scale and horizontal origin retain the artist's motion; ground contact stays fixed.
        frame = Image.new("RGBA", size)
        ground_offset = bottom - box[3] if grounded else 0
        frame.paste(image, (padding - left, padding - top + ground_offset))
        visible = frame.getchannel("A").getbbox()
        assert visible and visible[0] >= padding and visible[2] <= size[0] - padding
        if grounded:
            assert visible[3] == size[1] - padding
        assert image.getchannel("A").getextrema()[1] == 255
        # Cropping must retain every nontransparent pixel.
        assert sum(frame.getchannel("A").histogram()[1:]) == sum(image.getchannel("A").histogram()[1:])
        frame.save(destination / f"frame_{index:02d}.png", optimize=True)
        frames.append(frame)
    print(f"{destination.name}: {len(frames)} frames, {images[0].size} -> {size}")
    return frames


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Directory containing the supplied animation folders")
    args = parser.parse_args()
    preview = Image.new("RGB", (12 * 112, len(SEQUENCES) * 144), "#dfe8dd")
    draw = ImageDraw.Draw(preview)
    for row, (folder, name, count, grounded) in enumerate(SEQUENCES):
        frames = import_sequence(args.source / folder, ROOT / "app/src/main/assets" / name, count, grounded)
        for column, index in enumerate(range(min(len(frames), 12))):
            frame = frames[index].copy()
            frame.thumbnail((104, 108))
            x = column * 112 + (112 - frame.width) // 2
            y = row * 144 + 20 + 108 - frame.height
            preview.paste(frame, (x, y), frame)
            draw.line((column * 112, row * 144 + 128, (column + 1) * 112, row * 144 + 128), fill="#70836c")
            draw.text((column * 112 + 4, row * 144 + 130), str(index), fill="black")
        draw.text((4, row * 144 + 2), name, fill="black")
    output = ROOT / "build/animation-review"
    output.mkdir(parents=True, exist_ok=True)
    preview.save(output / "sequences.png")


if __name__ == "__main__":
    main()
