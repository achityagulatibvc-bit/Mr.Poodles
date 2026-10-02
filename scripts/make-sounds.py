"""Generate an original quiet two-note chime; no external audio assets."""
import math
from pathlib import Path
import struct
import wave

folder = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
folder.mkdir(parents=True, exist_ok=True)
rate, duration = 22050, 0.36
samples = []
for index in range(int(rate * duration)):
    t = index / rate
    value = 0.0
    for start, frequency in ((0.0, 783.99), (0.09, 1046.50)):
        age = t - start
        if age >= 0:
            envelope = min(age / .012, 1) * math.exp(-age * 15)
            value += .19 * envelope * math.sin(2 * math.pi * frequency * age)
    samples.append(struct.pack('<h', int(value * 32767)))
with wave.open(str(folder / 'poodles_chime.wav'), 'wb') as output:
    output.setnchannels(1)
    output.setsampwidth(2)
    output.setframerate(rate)
    output.writeframes(b''.join(samples))
print('Generated original Poodles chime.')
