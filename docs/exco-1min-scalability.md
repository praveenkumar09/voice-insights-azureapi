# Voice Insights: 1-minute speech · Scalability to other business units

**Job of this minute:** make EXCO believe that the second business unit is a *setting*, not a project.
**Length:** about 150 spoken words, so 1:00 with pauses. Say **Spark**, **suggestion**. No Juno.

**Facts behind it (checked in the code, `BusinessUnits.java`)**
- Eight business units are built in: Singapore, Thailand, Hong Kong, China, Malaysia, Indonesia, Philippines, Vietnam.
- An admin picks the unit in Admin → Business unit. It changes the identity in every prompt, the currency, the time zone, the languages offered, and what the speech model is told about the local setting.
- Campaigns are scoped to a business unit, and their dates run on that unit's clock.
- New products arrive by ingesting that unit's documents; the agents pick them up.

---

## The speech

*(Stand still. Slow. No video needed, or a still of the BU selector.)*

> "Every strong idea in this company dies in the same place. At the border of the next business unit.
>
> [pause]
>
> A new country means a new project. New team. New budget. New year.
>
> **Spark doesn't work like that.**
>
> [pause]
>
> We didn't build it for Singapore and then try to stretch it. One admin chooses the business unit. And Spark changes who she is. The currency. The time zone. The languages. How she listens to the local accent. The campaigns that apply.
>
> Eight business units are already in the product today.
>
> [pause]
>
> The only thing a unit brings is its own product documents. We load them. Spark reads them. Done.
>
> No rebuild. No new platform. No new vendor.
>
> [pause]
>
> The second business unit is not a project.
>
> **It is a setting.**"

**WOW · stand still:** "The second business unit is not a project. It is a setting."

## Say it honestly (EXCO will ask)

- **"Instantly" means the platform, not the content.** Each unit still needs its own product documents ingested, and its own compliance review before it goes live. Say so before they do.
- **Exchange rates are indicative placeholders** for the proof of concept. A real unit must supply its own documents priced in its own currency. Do not quote them.
- **Languages:** the unit's languages are offered; native review of the wording is still needed for each new language. English and Mandarin are best tested.
- **Capacity:** a pilot runs on standard Azure OpenAI quota. Many units at once need more quota or reserved capacity. Say this is a request to our own Azure team, not a rebuild.

## If asked "how long for the next BU?"

Do not give a date from the stage. Say: *"The platform is ready. The timeline is their documents and their Compliance review. We will agree it with each unit."*

## Cold line

*"The second business unit is not a project. It is a setting."*
