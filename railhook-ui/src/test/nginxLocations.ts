export interface NginxLocation {
  head: string;
  body: string;
}

export function nginxLocations(conf: string): NginxLocation[] {
  const out: NginxLocation[] = [];
  const re = /^\s*location\s+([^{]+)\{/gm;
  let m: RegExpExecArray | null;
  while ((m = re.exec(conf))) {
    let depth = 1;
    let i = re.lastIndex;
    while (depth > 0 && i < conf.length) {
      if (conf[i] === '{') depth++;
      else if (conf[i] === '}') depth--;
      i++;
    }
    out.push({ head: m[1].trim(), body: conf.slice(re.lastIndex, i - 1) });
  }
  return out;
}

export function nginxLocationBody(conf: string, head: string): string | undefined {
  return nginxLocations(conf).find((l) => l.head === head)?.body;
}
