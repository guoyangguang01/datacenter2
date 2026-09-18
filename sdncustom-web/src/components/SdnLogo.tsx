import React from 'react';

interface SdnLogoProps {
  size?: number;
  style?: React.CSSProperties;
}

/**
 * SDNCustom IoT Data Hub logo.
 *
 * Visual concept: a central hub connected to six outer measurement-point nodes,
 * with up/down arrows inside the hub representing data concentration & distribution.
 *
 * Hex layout (60° apart, flat-top):
 *   N=270°, NE=330°, SE=30°, S=90°, SW=150°, NW=210°
 */
export default function SdnLogo({ size = 32, style }: SdnLogoProps) {
  const cx = 32;
  const cy = 32;
  const outerR = 23; // distance from center to node centers
  const nodeR = 5;
  const hubR = 11;

  // 6 nodes evenly spaced, starting from top (270°), every 60°
  const angles = [270, 330, 30, 90, 150, 210];
  const nodePositions = angles.map((deg) => {
    const rad = (deg * Math.PI) / 180;
    return {
      x: cx + outerR * Math.cos(rad),
      y: cy + outerR * Math.sin(rad),
    };
  });

  // Alternate node colors: teal, blue, teal, blue, teal, blue
  const nodeColors = ['#36cfc9', '#4096ff', '#36cfc9', '#4096ff', '#36cfc9', '#4096ff'];

  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 64 64"
      width={size}
      height={size}
      fill="none"
      style={style}
    >
      <defs>
        <linearGradient id="sdn-hub-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#1677ff" />
          <stop offset="100%" stopColor="#0958d9" />
        </linearGradient>
        <linearGradient id="sdn-ring-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#4096ff" />
          <stop offset="100%" stopColor="#1677ff" />
        </linearGradient>
      </defs>

      {/* Background */}
      <circle cx={cx} cy={cy} r={31} fill="#f0f5ff" />

      {/* Connection lines: center → each node */}
      {nodePositions.map((p, i) => (
        <line
          key={`line-${i}`}
          x1={cx}
          y1={cy}
          x2={p.x}
          y2={p.y}
          stroke="#91caff"
          strokeWidth="1.5"
          opacity="0.7"
        />
      ))}

      {/* Outer ring connecting nodes */}
      <polygon
        points={nodePositions.map((p) => `${p.x},${p.y}`).join(' ')}
        fill="none"
        stroke="#bae0ff"
        strokeWidth="1"
        opacity="0.5"
      />

      {/* Measurement-point nodes */}
      {nodePositions.map((p, i) => (
        <circle key={`node-${i}`} cx={p.x} cy={p.y} r={nodeR} fill={nodeColors[i]} />
      ))}

      {/* Inner decorative ring */}
      <circle
        cx={cx}
        cy={cy}
        r={hubR + 3}
        fill="none"
        stroke="url(#sdn-ring-grad)"
        strokeWidth="2"
        opacity="0.4"
      />

      {/* Central hub */}
      <circle cx={cx} cy={cy} r={hubR} fill="url(#sdn-hub-grad)" />

      {/* Up arrow — data concentration (input) */}
      <path
        d="M28 33 L32 28 L36 33"
        stroke="white"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
      {/* Down arrow — data distribution (output) */}
      <path
        d="M28 36 L32 41 L36 36"
        stroke="white"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    </svg>
  );
}
