document.addEventListener('DOMContentLoaded', () => {
  const toggle = document.querySelector('.nav-toggle');
  const nav = document.querySelector('nav.tabs');
  if(!toggle || !nav) return;

  const close = () => {
    nav.classList.remove('is-open');
    toggle.setAttribute('aria-expanded','false');
    document.body.classList.remove('nav-locked');
  };
  const open = () => {
    nav.classList.add('is-open');
    toggle.setAttribute('aria-expanded','true');
    document.body.classList.add('nav-locked');
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
