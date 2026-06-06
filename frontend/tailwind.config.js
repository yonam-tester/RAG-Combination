/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        "on-primary": "#1000a9",
        "surface-dim": "#051424",
        "secondary": "#d0bcff",
        "on-tertiary-fixed-variant": "#6900b3",
        "surface-variant": "#273647",
        "surface-container-lowest": "#010f1f",
        "on-background": "#d4e4fa",
        "surface-container": "#122131",
        "on-tertiary-fixed": "#2c0051",
        "inverse-primary": "#494bd6",
        "primary-fixed-dim": "#c0c1ff",
        "on-secondary-fixed-variant": "#5516be",
        "surface-container-low": "#0d1c2d",
        "tertiary-fixed": "#f0dbff",
        "error": "#ffb4ab",
        "inverse-on-surface": "#233143",
        "on-primary-fixed-variant": "#2f2ebe",
        "on-surface-variant": "#c7c4d7",
        "tertiary-fixed-dim": "#ddb7ff",
        "on-secondary-container": "#c4abff",
        "surface-tint": "#c0c1ff",
        "outline": "#908fa0",
        "background": "#051424",
        "on-secondary": "#3c0091",
        "on-surface": "#d4e4fa",
        "on-tertiary-container": "#400071",
        "inverse-surface": "#d4e4fa",
        "surface": "#051424",
        "on-error-container": "#ffdad6",
        "surface-container-high": "#1c2b3c",
        "on-tertiary": "#490080",
        "on-primary-container": "#0d0096",
        "secondary-fixed": "#e9ddff",
        "primary-container": "#8083ff",
        "tertiary-container": "#b76dff",
        "primary": "#c0c1ff",
        "tertiary": "#ddb7ff",
        "on-error": "#690005",
        "surface-container-highest": "#273647",
        "secondary-container": "#571bc1",
        "on-primary-fixed": "#07006c",
        "surface-bright": "#2c3a4c",
        "outline-variant": "#464554",
        "error-container": "#93000a",
        "on-secondary-fixed": "#23005c",
        "primary-fixed": "#e1e0ff",
        "secondary-fixed-dim": "#d0bcff"
      },
      fontFamily: {
        'sans': ['Inter', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'sans-serif'],
        'mono': ['JetBrains Mono', 'Consolas', 'monospace'],
      },
      borderRadius: {
        "DEFAULT": "0.25rem",
        "lg": "0.5rem",
        "xl": "0.75rem",
        "2xl": "1rem",
        "full": "9999px"
      },
      spacing: {
        "sm": "12px",
        "margin-desktop": "40px",
        "xl": "80px",
        "md": "24px",
        "base": "8px",
        "lg": "48px",
        "margin-mobile": "16px",
        "gutter": "24px",
        "xs": "4px"
      },
      keyframes: {
        'fade-in': {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'slide-up': {
          '0%': { opacity: '0', transform: 'translateY(20px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'pulse-glow': {
          '0%, 100%': { boxShadow: '0 0 20px rgba(99, 102, 241, 0.15)' },
          '50%': { boxShadow: '0 0 35px rgba(99, 102, 241, 0.35)' },
        },
      },
      animation: {
        'fade-in': 'fade-in 0.4s ease-out both',
        'slide-up': 'slide-up 0.5s ease-out both',
        'pulse-glow': 'pulse-glow 2s ease-in-out infinite',
      },
      boxShadow: {
        'glow-indigo': '0 0 20px rgba(99, 102, 241, 0.3), 0 0 40px rgba(99, 102, 241, 0.15)',
        'glow-purple': '0 0 20px rgba(139, 92, 246, 0.3)',
      },
    },
  },
  plugins: [
    function ({ addUtilities }) {
      addUtilities({
        '.glow-indigo': {
          'box-shadow': '0 0 20px rgba(99, 102, 241, 0.3), 0 0 40px rgba(99, 102, 241, 0.1)',
        },
        '.glow-purple': {
          'box-shadow': '0 0 20px rgba(139, 92, 246, 0.3)',
        },
        '.animate-fade-in': {
          'animation': 'fade-in 0.4s ease-out both',
        },
        '.text-shadow-sm': {
          'text-shadow': '0 1px 2px rgba(0,0,0,0.3)',
        },
      });
    },
  ],
}
