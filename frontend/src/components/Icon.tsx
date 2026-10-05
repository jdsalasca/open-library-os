import type { SVGProps } from 'react';

const PATHS = {
  sun: 'M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0-13v2m0 16v2M4.2 4.2l1.4 1.4m12.8 12.8 1.4 1.4M3 12h2m14 0h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4',
  moon: 'M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z',
  book: 'M4 5.5A2.5 2.5 0 0 1 6.5 3H19v15H6.5A2.5 2.5 0 0 0 4 20.5V5.5ZM4 20.5A2.5 2.5 0 0 1 6.5 18H19v3H6.5A2.5 2.5 0 0 1 4 20.5Z',
  check: 'm4 12.5 5 5L20 6.5',
  alert: 'M12 8v5m0 3.5v.5M10.3 3.9 2.4 17.5A2 2 0 0 0 4.1 20.5h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z',
  inbox: 'M3 13h5l1.5 3h5L16 13h5M4.5 5h15l1.5 8v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4L4.5 5Z',
  menu: 'M4 7h16M4 12h16M4 17h16',
  close: 'M6 6l12 12M18 6 6 18',
  arrowRight: 'M5 12h14m-6-6 6 6-6 6',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Zm5.5-1.5L21 21',
  users:
    'M16 19v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 17.5V19M10 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM20 19v-1.5a3.5 3.5 0 0 0-2.6-3.4M15.5 4.2a3.5 3.5 0 0 1 0 6.6',
  shield: 'M12 3.5 19.5 6v5.5c0 4.2-3 7.6-7.5 9-4.5-1.4-7.5-4.8-7.5-9V6L12 3.5Z',
  catalog: 'M5 5.5h3V16H5zM10.5 3h3v13h-3zM16 7.5h3V16h-3zM3 17.5h18v2H3z',
  barcode: 'M3 5v14M6.5 5v14M10 5v10M13.5 5v14M17 5v10',
  loans: 'M4 6.5h6v3.5H4zM4 13h6v3.5H4zM13 6.5h7v3.5h-7zM13 13h7v3.5h-7z',
} as const;

export type IconName = keyof typeof PATHS;

type IconProps = SVGProps<SVGSVGElement> & {
  name: IconName;
  size?: number;
};

export function Icon({ name, size = 18, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}