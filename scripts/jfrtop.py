"""Инклюзивное число сэмплов по методам ru.lct из `jfr print --events jdk.ExecutionSample --stack-depth 200`."""
import collections, re, sys
inclusive = collections.Counter(); leaf = collections.Counter(); total = 0
frame = re.compile(r'^\s+([\w.$]+)\(')
def flush(frames):
    global total
    if not frames: return
    total += 1
    seen = set()
    own = [f for f in frames if f.startswith('ru.lct')]
    for f in own:
        if f not in seen:
            inclusive[f] += 1; seen.add(f)
    if own: leaf[own[0]] += 1
frames = []
for line in open(sys.argv[1]):
    if line.startswith('jdk.ExecutionSample'):
        flush(frames); frames = []
    m = frame.match(line)
    if m: frames.append(m.group(1))
flush(frames)
print('samples', total)
for f, c in inclusive.most_common(int(sys.argv[2]) if len(sys.argv) > 2 else 30):
    print(f'{100*c/total:5.1f}% incl  {100*leaf[f]/total:5.1f}% self-proj  {f}')
