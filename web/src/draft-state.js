/** Clear only the submitted version; newer input belongs to the user. */
export function acknowledgeSentDraft(drafts, personId, submittedText) {
  if(drafts.get(personId)!==submittedText) return false;
  drafts.set(personId,'');
  return true;
}
