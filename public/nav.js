document.addEventListener('DOMContentLoaded', () => {
  const toggle = document.querySelector('.nav-toggle');
  const nav = document.querySelector('nav.tabs');
  if(!toggle || !nav) return;

  // is-open switches display:none -> flex (needed so position:fixed even
  // applies); is-visible drives the actual opacity/transform transition
  // in public/style.css. Splitting these in two lets the open fade in and
  // the close fade out, instead of an instant display-property snap.
  const open = () => {
    nav.classList.add('is-open');
    toggle.setAttribute('aria-expanded','true');
    document.body.classList.add('nav-locked');
    // A single requestAnimationFrame can still land before the browser's
    // first paint of the is-open (opacity:0) state on some engines, which
    // skips straight to the end state instead of transitioning -- two
    // nested rAFs reliably guarantee that paint has already happened.
    requestAnimationFrame(() => {
      requestAnimationFrame(() => nav.classList.add('is-visible'));
    });
  };
  const close = () => {
    toggle.setAttribute('aria-expanded','false');
    nav.classList.remove('is-visible');
    let finished = false;
    const finish = () => {
      if(finished) return;
      finished = true;
      nav.classList.remove('is-open');
      // Deliberately not removed until here, not at the top of close():
      // nav-locked is what suppresses the header's backdrop-filter (see
      // public/style.css) while the panel is open. Releasing it immediately
      // let a scrolled header's blur animate back in while the panel was
      // still visibly fading out, competing for the same frames -- the
      // exact choppiness this was meant to fix, just in the opposite
      // direction. Waiting until the panel has actually finished hiding
      // means that blur-return transition never overlaps anything visible.
      document.body.classList.remove('nav-locked');
      nav.removeEventListener('transitionend', finish);
    };
    nav.addEventListener('transitionend', finish);
    // transitionend won't fire if a menu is closed before the open
    // animation ever ran (e.g. Escape hit immediately) -- this fallback
    // ensures is-open still gets cleared in that case.
    setTimeout(finish, 200);
  };

  toggle.addEventListener('click', () => {
    nav.classList.contains('is-open') ? close() : open();
  });

  nav.addEventListener('click', (e) => {
    if(e.target.tagName === 'A') close();
  });

  document.addEventListener('keydown', (e) => {
    if(e.key === 'Escape') close();
  });

  document.addEventListener('click', (e) => {
    if(nav.classList.contains('is-open') && !nav.contains(e.target) && !toggle.contains(e.target)){
      close();
    }
  });
});
