// A small icon set drawn for this dashboard: 24-unit box, 1.8 stroke, round
// caps, so every icon carries the same weight as the DSTNS controls beside it.

type IconProps = { title?: string };

function Svg({ title, children }: IconProps & { children: React.ReactNode }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round"
         strokeLinejoin="round" aria-hidden={title ? undefined : true} role={title ? "img" : undefined}>
      {title ? <title>{title}</title> : null}
      {children}
    </svg>
  );
}

export const Mark = () => (
  <svg viewBox="0 0 32 32" aria-hidden="true">
    <rect width="32" height="32" rx="8" fill="#0d2231" />
    <path d="M7 22 C 11 22, 11 10, 16 10 S 21 22, 25 22" fill="none" stroke="#37d7ff" strokeWidth="2.6" strokeLinecap="round" />
    <circle cx="7" cy="22" r="2.6" fill="#dfe9f2" />
    <circle cx="25" cy="22" r="2.6" fill="#dfe9f2" />
    <circle cx="16" cy="10" r="2.2" fill="#ffb938" />
  </svg>
);

export const Bus = (p: IconProps) => (
  <Svg {...p}><rect x="5" y="4" width="14" height="13" rx="3" /><path d="M5 11h14M8 20v-3M16 20v-3" /><circle cx="8.5" cy="14" r=".6" /><circle cx="15.5" cy="14" r=".6" /></Svg>
);
export const Walk = (p: IconProps) => (
  <Svg {...p}><circle cx="13" cy="4.5" r="1.8" /><path d="m9 21 2.5-6.5L14 17v4M11.5 14.5 12.5 9l3 3 2.5 1M12.5 9 9 10.5 7.5 13.5" /></Svg>
);
export const Change = (p: IconProps) => (
  <Svg {...p}><path d="M4 8h13l-3-3M20 16H7l3 3" /></Svg>
);
export const Clock = (p: IconProps) => (
  <Svg {...p}><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></Svg>
);
export const Pin = (p: IconProps) => (
  <Svg {...p}><path d="M12 21s6.5-6.1 6.5-11.2a6.5 6.5 0 0 0-13 0C5.5 14.9 12 21 12 21Z" /><circle cx="12" cy="9.8" r="2.2" /></Svg>
);
export const Swap = (p: IconProps) => (
  <Svg {...p}><path d="M7 4v16m0 0-3-3m3 3 3-3M17 20V4m0 0-3 3m3-3 3 3" /></Svg>
);
export const Dice = (p: IconProps) => (
  <Svg {...p}><rect x="4" y="4" width="16" height="16" rx="3.5" /><circle cx="9" cy="9" r=".9" /><circle cx="15" cy="15" r=".9" /><circle cx="15" cy="9" r=".9" /><circle cx="9" cy="15" r=".9" /></Svg>
);
export const Play = (p: IconProps) => (
  <Svg {...p}><path d="M8 5.5v13l10.5-6.5L8 5.5Z" /></Svg>
);
export const Pause = (p: IconProps) => (
  <Svg {...p}><path d="M8.5 5.5v13M15.5 5.5v13" /></Svg>
);
export const Plus = (p: IconProps) => (<Svg {...p}><path d="M12 5v14M5 12h14" /></Svg>);
export const Minus = (p: IconProps) => (<Svg {...p}><path d="M5 12h14" /></Svg>);
export const Frame = (p: IconProps) => (
  <Svg {...p}><path d="M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15" /></Svg>
);
export const Refresh = (p: IconProps) => (
  <Svg {...p}><path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v4h-4" /></Svg>
);
