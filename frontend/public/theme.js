// Resolves the theme before the first paint so a dark-mode reader never sees a
// white flash. This lives in its own file, loaded synchronously from <head>, on
// purpose: inline scripts are blocked by the Content-Security-Policy, and
// weakening it with 'unsafe-inline' would undo the point of having it.
//
// Plain ES5 syntax and no imports: this runs before any bundle exists.
(function () {
  try {
    var stored = localStorage.getItem('olo.theme');
    var dark = stored ? stored === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
  } catch {
    document.documentElement.dataset.theme = 'light';
  }
})();
