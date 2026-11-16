#!/usr/bin/env python3
"""Cuts the reference pictures (templates) for the detector out of three frames.

Usage: make_templates.py great_frame.png good_frame.png miss_frame.png out_dir
The frames must be full landscape screenshots scaled to a height of 540 px
(1212x540 for a 2424x1080 screen), each showing one judgment word on a dark,
quiet background.
"""
import struct, sys
import cv2, numpy as np

M = 6  # must equal JudgeDetector.MARGIN

def load(path):
    im = cv2.imread(path).astype(np.int32)  # BGR
    assert im.shape[0] == 540, "frame must be 540 px high"
    gray = (77 * im[..., 2] + 150 * im[..., 1] + 29 * im[..., 0] + 128) >> 8
    return gray, im, im.shape[1] // 2

def save(out, frame, x0, y0, w, h, clean=None):
    gray, bgr, cx = frame
    box = (slice(y0 - M, y0 + h + M), slice(x0 - M, x0 + w + M))
    p = gray[box].copy()
    bg = int(np.median(p[p < 60])) if (p < 60).any() else int(np.median(p))
    if clean:
        clean(p, bgr[box], bg)
    ph, pw = p.shape
    with open(out, 'wb') as f:
        f.write(struct.pack('>iiii', pw, ph, x0 - cx, y0))
        f.write(p.astype(np.uint8).tobytes())

great, good, miss, out = sys.argv[1:5]

def clean_great(p, bgr, bg):
    p[36:, 118:] = bg          # stray particle in the lower-right margin
save(f'{out}/great.tpl', load(great), 551, 364, 110, 41, clean_great)

def clean_good(p, bgr, bg):
    p[37:, :] = bg             # everything below the word (a square particle, the combo number)
    part = bgr[35:37, 22:42]   # the particle's top edge touches the bottom of "G" and "O"
    p[35:37, 22:42][part[..., 0] >= part[..., 1] - 5] = bg   # bluish = particle, green = letter
save(f'{out}/good.tpl', load(good), 563, 368, 86, 32, clean_good)

def clean_miss(p, bgr, bg):
    p[:7, :] = bg              # orange panel just above the word
save(f'{out}/miss.tpl', load(miss), 556, 366, 98, 39, clean_miss)
