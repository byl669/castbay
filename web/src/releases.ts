import { getCollection } from 'astro:content';
import type { Lang } from './i18n';

/** Compares version numbers ("1.0.9" < "1.0.10"), newest first. */
const newestFirst = (a: string, b: string) => {
  const x = a.split('.').map(Number);
  const y = b.split('.').map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    if ((x[i] ?? 0) !== (y[i] ?? 0)) return (y[i] ?? 0) - (x[i] ?? 0);
  }
  return 0;
};

/** The release notes in [lang], newest first. */
export async function releases(lang: Lang) {
  const all = await getCollection('releases', (entry) => entry.filePath?.includes(`/releases/${lang}/`));
  return all.sort((a, b) => newestFirst(a.data.version, b.data.version));
}

/** CastBay Desktop's release notes in [lang], newest first. */
export async function desktopReleases(lang: Lang) {
  const all = await getCollection('desktop', (entry) => entry.filePath?.includes(`/desktop/${lang}/`));
  return all.sort((a, b) => newestFirst(a.data.version, b.data.version));
}
