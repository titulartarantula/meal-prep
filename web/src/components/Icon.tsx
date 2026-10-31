import { PATHS, type IconName } from "./iconPaths";

export function Icon({ name, size = 18, className }: { name: IconName; size?: number; className?: string }) {
  return (
    <svg className={className ? `ic ${className}` : "ic"} width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: PATHS[name] }} />
  );
}

export function Star({ filled = true, size = 13 }: { filled?: boolean; size?: number }) {
  return (
    <svg className="star" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path fill={filled ? "currentColor" : "none"} stroke="currentColor" strokeWidth={filled ? 0 : 1.6}
        d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z" />
    </svg>
  );
}
