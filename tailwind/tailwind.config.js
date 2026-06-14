/** @type {import('tailwindcss').Config} */
module.exports = {
    content: [
        "./oswl-app/src/main/resources/templates/**/*.html",
        "./oswl-app/src/main/resources/static/js/**/*.js",
    ],
    theme: {
        extend: {
            fontFamily: {
                sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
            },
        },
    },
    plugins: [],
};
