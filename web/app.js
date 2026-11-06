// ==========================================================================
// OpenManager Web - Interactivity & Lightbox
// ==========================================================================

document.addEventListener('DOMContentLoaded', () => {
  // Mobile Navigation Toggle
  const menuToggle = document.getElementById('menuToggle');
  const navLinks = document.getElementById('navLinks');

  if (menuToggle && navLinks) {
    menuToggle.addEventListener('click', () => {
      navLinks.classList.toggle('active');
    });

    // Close menu when clicking outside or clicking any nav link
    navLinks.querySelectorAll('a').forEach(link => {
      link.addEventListener('click', () => {
        navLinks.classList.remove('active');
      });
    });
  }

  // Fetch Latest Release Tag dynamically from GitHub API
  fetchLatestRelease();
});

// Lightbox Functions
function openLightbox(src, caption) {
  const lightbox = document.getElementById('lightbox');
  const lightboxImg = document.getElementById('lightboxImg');
  const lightboxCaption = document.getElementById('lightboxCaption');

  if (lightbox && lightboxImg) {
    lightboxImg.src = src;
    lightboxCaption.textContent = caption || '';
    lightbox.classList.add('active');
    document.body.style.overflow = 'hidden';
  }
}

function closeLightbox() {
  const lightbox = document.getElementById('lightbox');
  if (lightbox) {
    lightbox.classList.remove('active');
    document.body.style.overflow = '';
  }
}

// Close lightbox on Escape key
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    closeLightbox();
  }
});

// Fetch Latest Release Tag dynamically from GitHub API
async function fetchLatestRelease() {
  const tagEl = document.getElementById('latestTag');
  if (!tagEl) return;

  try {
    const res = await fetch('https://api.github.com/repos/uamo11/OpenManager/releases/latest');
    if (res.ok) {
      const data = await res.json();
      if (data.tag_name) {
        tagEl.textContent = data.tag_name;
      }
    }
  } catch (err) {
    // Keep fallback v1.9.31
  }
}
