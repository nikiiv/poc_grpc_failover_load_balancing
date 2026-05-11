const PALETTE = [
  "#58a6ff", // blue
  "#a371f7", // purple
  "#f0883e", // orange
  "#3fb950", // green
  "#79c0ff", // light blue
  "#d2a8ff", // violet
  "#ffa657", // amber
  "#7ee787", // mint
];

export function serverColor(id: string): string {
  let h = 0;
  for (let i = 0; i < id.length; i++) {
    h = (h * 31 + id.charCodeAt(i)) | 0;
  }
  return PALETTE[Math.abs(h) % PALETTE.length];
}
